import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:greenv_capture/src/api/session_authenticator.dart';
import 'package:greenv_capture/src/api/http_capture_backend.dart';
import 'package:greenv_capture/src/api/http_operations_gateway.dart';
import 'package:greenv_capture/src/bootstrap/app_dependencies.dart';
import 'package:greenv_capture/src/bootstrap/capture_configuration.dart';
import 'package:greenv_capture/src/capture/browser_camera_recorder.dart';
import 'package:greenv_capture/src/capture/capture_coordinator.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/capture/capture_runtime.dart';
import 'package:greenv_capture/src/capture/phone_telemetry_collector.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';
import 'package:greenv_capture/src/identifier/uuid_v7_identifier_adapter.dart';
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
    // `?session=` pins the id through the same port that otherwise mints one, so the coordinator
    // has a single way of getting an identifier rather than an override beside it.
    identifierGenerator: requestedSession == null || requestedSession.isEmpty
        ? UuidV7IdentifierAdapter()
        : _FixedIdentifierGenerator(requestedSession),
    monotonicNanos: monotonicClock.nowNanos,
  );
  uploader.syncSoon();
  // `?autostart=1` records without a click, so an automated browser can drive a whole capture.
  if (Uri.base.queryParameters['autostart'] == '1') unawaited(capture.start());
  return AppDependencies(
    capture: capture,
    authenticator: authenticator,
    operations: HttpOperationsGateway(
      baseUri: CaptureConfiguration.apiUri,
      auth: authenticator,
    ),
  );
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
    identifierGenerator: UuidV7IdentifierAdapter(),
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
    // Sample rows so the layout has something to lay out. This is the design preview, which
    // has to be asked for with a build flag; a real build never reaches it.
    operations: _PreviewOperationsGateway(),
    mockedPreview: true,
  );
}

/// Enough rows to see every screen with content. Not data: the design preview exists to look
/// at layout, and these numbers are chosen to exercise each branch — a stretch at each level,
/// one with no height, one order per status.
final class _PreviewOperationsGateway implements OperationsGateway {
  static const _session = '11111111-1111-4111-8111-111111111111';

  static final List<MeasuredStretch> _stretches = [
    MeasuredStretch(
      sessionId: _session,
      segmentIndex: 0,
      capturedAt: DateTime.utc(2026, 9, 11, 18, 51),
      level: 3,
      extent95P95M: 3.18,
      extent95MaxM: 3.9,
      cellsMeasured: 96,
      cellsAbstained: 144,
      coverage: 0.40,
      centreLat: -23.5738,
      centreLon: -46.6315,
      locationQuality: 'poor',
      placeLabel: 'Rodovia Anhanguera',
      placeDetail: 'Osasco · São Paulo',
      placeRoad: 'SP-021',
      placeKm: 12,
    ),
    MeasuredStretch(
      sessionId: _session,
      segmentIndex: 1,
      capturedAt: DateTime.utc(2026, 9, 11, 18, 52),
      level: 3,
      extent95P95M: 0.86,
      extent95MaxM: 1.2,
      cellsMeasured: 148,
      cellsAbstained: 92,
      coverage: 0.61,
      centreLat: -23.5742,
      centreLon: -46.6338,
      locationQuality: 'good',
      placeLabel: 'Avenida Professor Rubens Gomes de Souza',
      placeDetail: 'Jardim dos Estados · São Paulo',
      placeHouseNumber: '1621',
    ),
    MeasuredStretch(
      sessionId: _session,
      segmentIndex: 2,
      capturedAt: DateTime.utc(2026, 9, 11, 18, 52, 20),
      level: 2,
      extent95P95M: 0.22,
      extent95MaxM: 0.4,
      cellsMeasured: 40,
      cellsAbstained: 12,
      coverage: 0.7,
      centreLat: -23.5747,
      centreLon: -46.6353,
      locationQuality: 'good',
      placeLabel: 'Rua Miranda Guerra',
      placeDetail: 'Jardim dos Estados · São Paulo',
    ),
    MeasuredStretch(
      sessionId: _session,
      segmentIndex: 3,
      capturedAt: DateTime.utc(2026, 9, 11, 18, 52, 30),
      level: 1,
      extent95P95M: 0.07,
      extent95MaxM: 0.1,
      cellsMeasured: 30,
      cellsAbstained: 4,
      coverage: 0.8,
      centreLat: -23.5749,
      centreLon: -46.6360,
      locationQuality: 'degraded',
      placeLabel: 'Rua Topázio',
      placeDetail: 'Liberdade · São Paulo',
      placeHouseNumber: '450',
    ),
    MeasuredStretch(
      sessionId: _session,
      segmentIndex: 4,
      capturedAt: DateTime.utc(2026, 9, 11, 18, 52, 40),
      cellsMeasured: 0,
      cellsAbstained: 22,
      coverage: 0,
      centreLat: -23.5751,
      centreLon: -46.6366,
      locationQuality: 'good',
      placeLabel: 'Rua Braz Cubas',
      placeDetail: 'Liberdade · São Paulo',
    ),
  ];

  static final List<Team> _teams = [
    const Team(
      teamId: 't1',
      name: 'Equipe 1 – Zona Norte',
      shortName: 'Equipe 1',
      initials: 'E1',
      region: 'KM 0 – 10',
      status: TeamStatus.inField,
      pendingOrders: 1,
      inProgressOrders: 0,
      completedOrders: 2,
    ),
    const Team(
      teamId: 't2',
      name: 'Equipe 2 – Zona Sul',
      shortName: 'Equipe 2',
      initials: 'E2',
      region: 'KM 10 – 20',
      status: TeamStatus.available,
      pendingOrders: 1,
      inProgressOrders: 2,
      completedOrders: 2,
    ),
    const Team(
      teamId: 't3',
      name: 'Terceirizada',
      shortName: 'Terceirizada',
      initials: 'TC',
      region: 'Sob demanda',
      status: TeamStatus.offDuty,
      pendingOrders: 0,
      inProgressOrders: 0,
      completedOrders: 0,
    ),
  ];

  final List<ServiceOrder> _orders = [
    ServiceOrder(
      orderId: 'o1',
      reference: 'OS-ROÇ-202609-1001',
      status: OrderStatus.pending,
      priority: OrderPriority.high,
      teamId: 't1',
      createdAt: DateTime.utc(2026, 9, 13),
      equipment: 'Trator com braço articulado',
      areaSquareMetres: 788,
      vegetationLevel: 3,
      targetCount: 1,
    ),
    ServiceOrder(
      orderId: 'o2',
      reference: 'OS-ROÇ-202609-1002',
      status: OrderStatus.inProgress,
      priority: OrderPriority.urgent,
      teamId: 't2',
      createdAt: DateTime.utc(2026, 9, 12),
      equipment: 'Roçadeira costal',
      areaSquareMetres: 220,
      vegetationLevel: 2,
      targetCount: 1,
    ),
    ServiceOrder(
      orderId: 'o3',
      reference: 'OS-ROÇ-202609-1003',
      status: OrderStatus.completed,
      priority: OrderPriority.medium,
      teamId: 't1',
      createdAt: DateTime.utc(2026, 9, 10),
      equipment: 'Roçadeira costal',
      areaSquareMetres: 117,
      vegetationLevel: 1,
      targetCount: 1,
    ),
  ];

  @override
  Future<ReadingsSummary> readingsSummary() async => const ReadingsSummary(
    total: 5,
    countsByLevel: {0: 1, 1: 1, 2: 1, 3: 2},
    tallestM: 3.18,
  );

  @override
  Future<PageOf<MeasuredStretch>> stretches({
    int? level,
    int limit = 25,
    int offset = 0,
  }) async {
    final rows = level == null
        ? _stretches
        : _stretches.where((s) => s.vegetationLevel.number == level).toList();
    return PageOf(
      items: rows.skip(offset).take(limit).toList(),
      total: rows.length,
      hasMore: false,
    );
  }

  @override
  Future<PageOf<CaptureSessionSummary>> sessions({int limit = 10}) async =>
      PageOf(
        items: [
          CaptureSessionSummary(
            sessionId: _session,
            startedAt: DateTime.utc(2026, 9, 11, 18, 51),
            segmentCount: 8,
            measuredSegmentCount: 7,
          ),
        ],
        total: 1,
        hasMore: false,
      );

  @override
  Future<List<MeasuredStretch>> sessionStretches(String sessionId) async =>
      _stretches.where((s) => s.sessionId == sessionId).toList();

  // No photographs in the design preview: the frames live behind the API, and this build has
  // none. The sheet says so rather than showing a placeholder that looks like a capture.
  @override
  Future<List<SampledFrame>> frames(String sessionId, int segmentIndex) async =>
      const [];

  @override
  Future<FrameReadings?> frameReadings(
    String sessionId,
    int segmentIndex,
    String fileName,
  ) async => null;

  @override
  Future<Uint8List> frameImage(String imageUrl) async =>
      throw UnsupportedError('a prévia de design não carrega fotos');

  @override
  Future<List<Team>> teams() async => _teams;

  @override
  Future<PageOf<ServiceOrder>> orders({int limit = 50}) async =>
      PageOf(items: _orders, total: _orders.length, hasMore: false);

  @override
  Future<ServiceOrder> openOrder(OrderDraft draft) async {
    final order = ServiceOrder(
      orderId: 'o${_orders.length + 1}',
      reference: 'OS-ROÇ-202609-${1001 + _orders.length}',
      status: OrderStatus.pending,
      priority: draft.priority,
      teamId: draft.teamId,
      createdAt: DateTime.now().toUtc(),
      equipment: 'Trator com braço articulado',
      areaSquareMetres: 100.0 * draft.targets.length,
      vegetationLevel: 3,
      targetCount: draft.targets.length,
    );
    _orders.insert(0, order);
    return order;
  }
}

final class _PreviewRecorder implements SegmentRecorder {
  bool _initialized = false;

  @override
  bool get isInitialized => _initialized;

  @override
  double? get capturedFrameRate => null;

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

/// Hands back one identifier that was chosen from outside, so `?session=<uuid>` can pin a capture
/// and it can be followed with `GET /v2/capture-sessions/<uuid>` while it is still running.
final class _FixedIdentifierGenerator implements IdentifierGenerator {
  const _FixedIdentifierGenerator(this._value);

  final String _value;

  @override
  String next() => _value;
}
