import 'package:flutter/widgets.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';

final class FakeIdentifierGenerator implements IdentifierGenerator {
  FakeIdentifierGenerator(this.identifier);

  final String identifier;

  @override
  String next() => identifier;
}

final class FakeRecorder implements SegmentRecorder {
  bool initialized = false;
  int starts = 0;
  int stops = 0;

  @override
  bool get isInitialized => initialized;

  @override
  double? get capturedFrameRate => null;

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

  /// Settable, because how vague the fix is decides what the capture screen says about it.
  double? horizontalAccuracyMeters = 4.2;

  @override
  double? get latestHorizontalAccuracyMeters => horizontalAccuracyMeters;

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
  Future<void> completeSession(QueuedSession session) async => completions += 1;
}

/// Opens sessions but refuses the bytes, which is the failure the queue exists for.
final class FailingUploadBackend implements CaptureBackend {
  int sessions = 0;
  int completions = 0;

  @override
  Future<void> ensureSession(QueuedSession session) async => sessions += 1;

  @override
  Future<void> uploadSegment(QueuedSegment segment) async =>
      throw const SocketExceptionForTest();

  @override
  Future<void> completeSession(QueuedSession session) async => completions += 1;
}

final class SocketExceptionForTest implements Exception {
  const SocketExceptionForTest();

  @override
  String toString() => 'offline';
}

/// The read side, scripted. A test sets what the API would answer and watches what was asked.
final class FakeOperationsGateway implements OperationsGateway {
  ReadingsSummary summary = ReadingsSummary.empty;
  List<MeasuredStretch> stretchRows = const [];
  List<CaptureSessionSummary> sessionRows = const [];
  List<Team> teamRows = const [];
  List<ServiceOrder> orderRows = const [];

  /// Thrown by every call when set, so a screen's failure path can be exercised.
  Object? failure;

  /// Every draft handed to [openOrder], in order.
  final List<OrderDraft> opened = [];

  int? lastLevelFilter;

  void _maybeFail() {
    if (failure != null) throw failure!;
  }

  @override
  Future<ReadingsSummary> readingsSummary() async {
    _maybeFail();
    return summary;
  }

  @override
  Future<PageOf<MeasuredStretch>> stretches({
    int? level,
    int limit = 25,
    int offset = 0,
  }) async {
    _maybeFail();
    lastLevelFilter = level;
    final rows = level == null
        ? stretchRows
        : stretchRows.where((s) => s.vegetationLevel.number == level).toList();
    final page = rows.skip(offset).take(limit).toList();
    return PageOf(
      items: page,
      total: rows.length,
      hasMore: offset + page.length < rows.length,
    );
  }

  @override
  Future<PageOf<CaptureSessionSummary>> sessions({int limit = 10}) async {
    _maybeFail();
    final page = sessionRows.take(limit).toList();
    return PageOf(
      items: page,
      total: sessionRows.length,
      hasMore: page.length < sessionRows.length,
    );
  }

  @override
  Future<List<Team>> teams() async {
    _maybeFail();
    return teamRows;
  }

  @override
  Future<PageOf<ServiceOrder>> orders({int limit = 50}) async {
    _maybeFail();
    return PageOf(items: orderRows, total: orderRows.length, hasMore: false);
  }

  @override
  Future<ServiceOrder> openOrder(OrderDraft draft) async {
    _maybeFail();
    opened.add(draft);
    final order = ServiceOrder(
      orderId: 'order-${opened.length}',
      reference: 'OS-ROÇ-202609-${1000 + opened.length}',
      status: OrderStatus.pending,
      priority: draft.priority,
      teamId: draft.teamId,
      createdAt: DateTime.utc(2026, 9, 13),
      targetCount: draft.targets.length,
    );
    orderRows = [order, ...orderRows];
    return order;
  }
}
