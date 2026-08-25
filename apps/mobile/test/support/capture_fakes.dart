import 'package:flutter/widgets.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

final class FakeRecorder implements SegmentRecorder {
  bool initialized = false;
  int starts = 0;
  int stops = 0;

  @override
  bool get isInitialized => initialized;

  @override
  Widget buildPreview() => const ColoredBox(color: Color(0xFF33283A));

  @override
  Future<void> initialize() async => initialized = true;

  @override
  Future<void> start() async => starts += 1;

  @override
  Future<RecordedVideo> stop() async {
    stops += 1;
    return RecordedVideo(
      path: 'memory://segment-$stops.mp4',
      durationMillis: 10000,
    );
  }

  @override
  Future<void> dispose() async {}
}

final class FakeTelemetry implements TelemetryCollector {
  int begins = 0;
  int finishes = 0;

  @override
  double? get latestHorizontalAccuracyMeters => 4.2;

  @override
  double? get latestSpeedMetersPerSecond => 8;

  @override
  Future<void> beginSegment({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int monotonicStartNanos,
  }) async {
    begins += 1;
  }

  @override
  Future<SegmentTelemetryDocument> finishSegment() async {
    finishes += 1;
    return const SegmentTelemetryDocument({'schemaVersion': 1});
  }

  @override
  Future<void> dispose() async {}
}

final class FakeLease implements ForegroundLease {
  int acquired = 0;
  int released = 0;

  @override
  Future<void> acquire() async => acquired += 1;

  @override
  Future<void> release() async => released += 1;
}

final class FakeScheduler implements SegmentScheduler {
  Duration? duration;
  Future<void> Function()? callback;

  @override
  void schedule(Duration duration, Future<void> Function() callback) {
    this.duration = duration;
    this.callback = callback;
  }

  @override
  void cancel() {
    duration = null;
  }

  Future<void> fire() async {
    final scheduled = callback;
    callback = null;
    if (scheduled != null) await scheduled();
  }
}

final class FakeBackend implements CaptureBackend {
  bool online = true;
  String workerState = 'queued';
  int sessions = 0;
  int uploads = 0;
  int completions = 0;

  @override
  Future<void> ensureSession(QueuedSession session) async {
    sessions += 1;
    if (!online) throw const SocketExceptionForTest();
  }

  @override
  Future<void> uploadSegment(QueuedSegment segment) async {
    uploads += 1;
    if (!online) throw const SocketExceptionForTest();
  }

  @override
  Future<String> segmentState(QueuedSegment segment) async => workerState;

  @override
  Future<void> completeSession(QueuedSession session) async => completions += 1;
}

final class SocketExceptionForTest implements Exception {
  const SocketExceptionForTest();

  @override
  String toString() => 'offline';
}
