import 'package:flutter/foundation.dart';
import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

final class MemoryCaptureQueue implements CaptureQueue {
  final List<QueuedSession> _sessions = [];
  final ValueNotifier<int> _backlog = ValueNotifier(0);

  @override
  ValueListenable<int> get backlog => _backlog;

  @override
  Future<List<QueuedSession>> sessions() async => List.unmodifiable(_sessions);

  @override
  Future<void> beginSession(QueuedSession session) async {
    if (_sessions.every((item) => item.sessionId != session.sessionId)) {
      _sessions.add(session);
    }
  }

  @override
  Future<QueuedSegment> enqueueCaptured({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int durationMillis,
    required String sourceVideoPath,
    required SegmentTelemetryDocument telemetry,
  }) async {
    final queued = QueuedSegment(
      sessionId: sessionId,
      segmentIndex: segmentIndex,
      idempotencyKey: '$sessionId:$segmentIndex',
      capturedAtUtc: capturedAtUtc,
      durationMillis: durationMillis,
      videoPath: sourceVideoPath,
      videoSha256: List.filled(64, '0').join(),
      telemetryPath: 'memory://$sessionId/$segmentIndex/telemetry.json',
      telemetrySha256: List.filled(64, '0').join(),
    );
    final position = _sessions.indexWhere(
      (item) => item.sessionId == sessionId,
    );
    _sessions[position] = _sessions[position].copyWith(
      segments: [..._sessions[position].segments, queued],
    );
    _refresh();
    return queued;
  }

  @override
  Future<void> closeSession(
    String sessionId, {
    required DateTime endedAtUtc,
    required int lastSegmentIndex,
  }) async {
    final position = _sessions.indexWhere(
      (item) => item.sessionId == sessionId,
    );
    _sessions[position] = _sessions[position].copyWith(
      endedAtUtc: endedAtUtc,
      lastSegmentIndex: lastSegmentIndex,
    );
  }

  @override
  Future<void> updateSegment(QueuedSegment segment) async {
    final sessionPosition = _sessions.indexWhere(
      (item) => item.sessionId == segment.sessionId,
    );
    final session = _sessions[sessionPosition];
    final segments = [...session.segments];
    final position = segments.indexWhere(
      (item) => item.segmentIndex == segment.segmentIndex,
    );
    segments[position] = segment;
    _sessions[sessionPosition] = session.copyWith(segments: segments);
  }

  @override
  Future<void> removeVerifiedSegment(QueuedSegment segment) async {
    final position = _sessions.indexWhere(
      (item) => item.sessionId == segment.sessionId,
    );
    final session = _sessions[position];
    _sessions[position] = session.copyWith(
      segments: session.segments
          .where((item) => item.segmentIndex != segment.segmentIndex)
          .toList(),
    );
    _refresh();
  }

  @override
  Future<void> markCompletionSent(String sessionId) async {
    final position = _sessions.indexWhere(
      (item) => item.sessionId == sessionId,
    );
    _sessions[position] = _sessions[position].copyWith(completionSent: true);
  }

  void _refresh() {
    _backlog.value = _sessions.fold(
      0,
      (count, session) => count + session.segments.length,
    );
  }
}
