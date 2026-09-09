// ignore_for_file: prefer_initializing_formals

import 'dart:async';

import 'package:greenv_capture/src/capture/capture_ports.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

/// Drains the offline queue into the API.
///
/// The queue's contract is delivery, not analysis: a segment leaves it as soon as the API has
/// taken its bytes, and a session leaves it as soon as its closure has been announced. What the
/// worker then makes of a segment is never asked about here. Polling for it kept finished captures
/// on screen, held the device's copies of the video, and put one request per segment per tick on a
/// connection whose whole reason for existing is uploading the next one.
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
      // Before ensureSession, deliberately. A finished session that stayed in the queue was
      // re-announced on every tick - one POST per session, for ever, growing with each capture.
      if (_isFinished(session)) {
        await _queue.removeCompletedSession(session.sessionId);
        continue;
      }
      try {
        await _backend.ensureSession(session);
      } on Object {
        continue;
      }
      for (final segment in session.segments) {
        await _syncSegment(segment);
      }
      await _completeIfDelivered(session.sessionId);
    }
  }

  void syncSoon() {
    unawaited(syncOnce());
  }

  /// Nothing left to send: the recording is over, every segment was accepted, and the API was told
  /// the session ended.
  static bool _isFinished(QueuedSession session) =>
      session.isClosed && session.completionSent && session.segments.isEmpty;

  Future<void> _syncSegment(QueuedSegment segment) async {
    try {
      // A segment left in `awaitingVerification` was uploaded by a build that then waited for the
      // worker. Its bytes are already there, so it is dropped rather than sent a second time.
      if (segment.state != SegmentUploadState.awaitingVerification) {
        await _backend.uploadSegment(segment);
      }
      await _queue.removeDeliveredSegment(segment);
    } on Object catch (error) {
      await _queue.updateSegment(
        segment.copyWith(
          state: SegmentUploadState.failed,
          lastError: error.toString(),
        ),
      );
    }
  }

  Future<void> _completeIfDelivered(String sessionId) async {
    final sessions = await _queue.sessions();
    QueuedSession? current;
    for (final session in sessions) {
      if (session.sessionId == sessionId) current = session;
    }
    if (current == null || !current.isClosed || current.completionSent) return;
    // Empty means every segment was delivered and dropped. One that failed is still here, and
    // holds the session open until it goes through.
    if (current.segments.isNotEmpty) return;
    try {
      await _backend.completeSession(current);
      await _queue.markCompletionSent(current.sessionId);
    } on Object {
      // A later sync retries the idempotent session completion.
    }
  }
}
