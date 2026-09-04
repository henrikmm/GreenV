import 'dart:async';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/api/http_capture_backend.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/bootstrap/capture_configuration.dart';
import 'package:greenv_capture/src/capture/browser_camera_recorder.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/capture/capture_runtime.dart';
import 'package:greenv_capture/src/capture/phone_telemetry_collector.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/storage/browser_capture_queue.dart';
import 'package:greenv_capture/src/storage/memory_session_store.dart';
import 'package:greenv_capture/src/storage/memory_capture_queue.dart';
import 'package:greenv_capture/src/upload/queue_uploader.dart';
import 'package:uuid/uuid.dart';

Future<AppDependencies> createAppDependencies() async =>
    CaptureConfiguration.webCaptureEnabled
    ? _createBrowserCaptureDependencies()
    : _createPreviewDependencies();

/// The workstation capture build. It opens the real webcam, samples whatever GNSS the browser
/// offers and uploads to the configured API, so a laptop can exercise the deployed pipeline end to
/// end. Its queue lives in memory: a reload discards anything the worker has not yet verified.
Future<AppDependencies> _createBrowserCaptureDependencies() async {
  final queue = BrowserCaptureQueue();
  final monotonicClock = MonotonicClock();

  // In memory, like the queue beside it: a page has nowhere to put a credential that is safe from
  // script, so a reload asks for the password again.
  final authenticator = SessionAuthenticator(
    tokenUri: CaptureConfiguration.tokenUri,
    store: MemorySessionStore(),
  );
  final uploader = QueueUploader(
    queue: queue,
    backend: HttpCaptureBackend(
      baseUri: CaptureConfiguration.apiUri,
      content: queue,
      bearerToken: CaptureConfiguration.apiToken,
      // Every upload carries the token of whoever signed in, so a capture is attributable to a
      // person rather than to a credential shared by the whole pilot.
      auth: authenticator,
    ),
  );
  // `?session=<uuid>` pins the session identifier so a capture started here can be followed from
  // outside the browser with `GET /v2/capture-sessions/<uuid>`.
  final requestedSession = Uri.base.queryParameters['session'];
  final capture = CaptureCoordinator(
    deviceId: 'greenv-browser-${const Uuid().v4()}',
    recorder: BrowserCameraRecorder(
      preferredLabel: Uri.base.queryParameters['camera'],
    ),
    telemetry: PhoneTelemetryCollector(monotonicClock.nowNanos),
    queue: queue,
    uploader: uploader,
    foregroundLease: _NoopForegroundLease(),
    scheduler: TimerSegmentScheduler(),
    monotonicNanos: monotonicClock.nowNanos,
    newSessionId: requestedSession == null || requestedSession.isEmpty
        ? null
        : () => requestedSession,
  );
  uploader.syncSoon();
  // `?autostart=1` records without a click, so an automated browser can drive a whole capture.
  if (Uri.base.queryParameters['autostart'] == '1') unawaited(capture.start());
  return AppDependencies(capture: capture, authenticator: authenticator);
}

Future<AppDependencies> _createPreviewDependencies() async {
  final queue = MemoryCaptureQueue();
  final recorder = _PreviewRecorder();
  final offline = Uri.base.queryParameters['offline'] == '1';
  final uploader = QueueUploader(
    queue: queue,
    backend: _PreviewBackend(online: !offline),
  );
  final capture = CaptureCoordinator(
    deviceId: 'web-design-preview',
    recorder: recorder,
    telemetry: _PreviewTelemetry(),
    queue: queue,
    uploader: uploader,
    foregroundLease: _NoopForegroundLease(),
    scheduler: _PreviewScheduler(),
    monotonicNanos: () => DateTime.now().microsecondsSinceEpoch * 1000,
  );
  if (Uri.base.queryParameters['phase'] == 'preparing') {
    capture.phase = CapturePhase.preparing;
  }
  if (Uri.base.queryParameters['phase'] == 'error') {
    capture.phase = CapturePhase.error;
    capture.errorMessage = 'Não foi possível acessar a câmera. Revise a permissão e tente novamente.';
  }
  final authenticator = SessionAuthenticator(
    tokenUri: CaptureConfiguration.tokenUri,
    store: MemorySessionStore(),
  );
  return AppDependencies(
    capture: capture,
    authenticator: authenticator,
    mockedPreview: true,
  );
}

final class _PreviewRecorder implements SegmentRecorder {
  bool _initialized = false;

  @override
  bool get isInitialized => _initialized;

  @override
  Widget buildPreview() => const ColoredBox(
    color: Color(0xFF232027),
    child: Center(
      child: Icon(Icons.route_rounded, size: 72, color: Color(0xFFBDA7CC)),
    ),
  );

  @override
  Future<void> initialize() async => _initialized = true;

  @override
  Future<void> start() async {}

  @override
  Future<RecordedVideo> stop() async =>
      const RecordedVideo(path: 'memory://preview.mp4', durationMillis: 10000);

  @override
  Future<void> dispose() async {}
}

final class _PreviewTelemetry implements TelemetryCollector {
  String? _sessionId;
  int? _segmentIndex;
  DateTime? _capturedAt;
  int? _monotonicStart;

  @override
  double? get latestHorizontalAccuracyMeters => 4.2;

  @override
  double? get latestSpeedMetersPerSecond => 12.4;

  @override
  Future<void> beginSegment({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int monotonicStartNanos,
  }) async {
    _sessionId = sessionId;
    _segmentIndex = segmentIndex;
    _capturedAt = capturedAtUtc;
    _monotonicStart = monotonicStartNanos;
  }

  @override
  Future<SegmentTelemetryDocument> finishSegment() async =>
      SegmentTelemetryDocument({
        'schemaVersion': 1,
        'sessionId': _sessionId,
        'segmentIndex': _segmentIndex,
        'capturedAtUtc': _capturedAt!.toIso8601String(),
        'monotonicStartNanos': _monotonicStart,
        'frameClockSource': 'segment_anchor',
        'locations': const [],
        'motions': const [],
      });

  @override
  Future<void> dispose() async {}
}

final class _PreviewBackend implements CaptureBackend {
  const _PreviewBackend({required this.online});

  final bool online;

  @override
  Future<void> completeSession(QueuedSession session) async {}

  @override
  Future<void> ensureSession(QueuedSession session) async {
    if (!online) throw StateError('offline design preview');
  }

  @override
  Future<String> segmentState(QueuedSegment segment) async => 'ready';

  @override
  Future<void> uploadSegment(QueuedSegment segment) async {
    if (!online) throw StateError('offline design preview');
  }
}

final class _NoopForegroundLease implements ForegroundLease {
  @override
  Future<void> acquire() async {}

  @override
  Future<void> release() async {}
}

final class _PreviewScheduler implements SegmentScheduler {
  @override
  void cancel() {}

  @override
  void schedule(Duration duration, Future<void> Function() callback) {}
}
