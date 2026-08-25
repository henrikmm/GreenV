// ignore_for_file: prefer_initializing_formals

import 'dart:async';

import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

final class QueueUploader {
  QueueUploader({required CaptureQueue queue, required CaptureBackend backend})
    : _queue = queue,
      _backend = backend;

  final CaptureQueue _queue;
  final CaptureBackend _backend;
  Future<void>? _activeSync;

  Future<void> syncOnce() {
    final active = _activeSync;
    if (active != null) return active;
    final running = _run();
    _activeSync = running.whenComplete(() => _activeSync = null);
    return _activeSync!;
  }

  Future<void> _run() async {
    for (final session in await _queue.sessions()) {
      try {
        await _backend.ensureSession(session);
      } on Object {
        continue;
      }
      for (final segment in session.segments) {
        await _syncSegment(segment);
      }
      await _completeIfAccepted(session.sessionId);
    }
  }

  void syncSoon() {
    unawaited(syncOnce());
  }

  Future<void> _syncSegment(QueuedSegment segment) async {
    try {
      var current = segment;
      if (current.state != SegmentUploadState.awaitingVerification) {
        await _backend.uploadSegment(current);
        current = current.copyWith(
          state: SegmentUploadState.awaitingVerification,
          clearError: true,
        );
        await _queue.updateSegment(current);
      }
      final state = await _backend.segmentState(current);
      if (state == 'ready') {
        await _queue.removeVerifiedSegment(current);
      } else if (state == 'failed') {
        await _queue.updateSegment(
          current.copyWith(
            state: SegmentUploadState.failed,
            lastError: 'worker rejected the segment',
          ),
        );
      }
    } on Object catch (error) {
      await _queue.updateSegment(
        segment.copyWith(
          state: SegmentUploadState.failed,
          lastError: error.toString(),
        ),
      );
    }
  }

  Future<void> _completeIfAccepted(String sessionId) async {
    final sessions = await _queue.sessions();
    QueuedSession? current;
    for (final session in sessions) {
      if (session.sessionId == sessionId) current = session;
    }
    if (current == null || !current.isClosed || current.completionSent) return;
    final allAccepted = current.segments.every(
      (segment) => segment.state == SegmentUploadState.awaitingVerification,
    );
    if (!allAccepted) return;
    try {
      await _backend.completeSession(current);
      await _queue.markCompletionSent(current.sessionId);
    } on Object {
      // A later sync retries the idempotent session completion.
    }
  }
}
