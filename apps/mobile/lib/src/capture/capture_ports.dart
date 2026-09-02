import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

abstract interface class SegmentRecorder {
  bool get isInitialized;
  Widget buildPreview();
  Future<void> initialize();
  Future<void> start();
  Future<RecordedVideo> stop();
  Future<void> dispose();
}

abstract interface class TelemetryCollector {
  double? get latestSpeedMetersPerSecond;
  double? get latestHorizontalAccuracyMeters;

  Future<void> beginSegment({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int monotonicStartNanos,
  });

  Future<SegmentTelemetryDocument> finishSegment();
  Future<void> dispose();
}

abstract interface class ForegroundLease {
  Future<void> acquire();
  Future<void> release();
}

abstract interface class SegmentScheduler {
  void schedule(Duration duration, Future<void> Function() callback);
  void cancel();
}

abstract interface class CaptureQueue {
  ValueListenable<int> get backlog;

  Future<List<QueuedSession>> sessions();
  Future<void> beginSession(QueuedSession session);
  Future<QueuedSegment> enqueueCaptured({
    required String sessionId,
    required int segmentIndex,
    required DateTime capturedAtUtc,
    required int durationMillis,
    required String sourceVideoPath,
    required String videoContentType,
    required SegmentTelemetryDocument telemetry,
  });
  Future<void> closeSession(
    String sessionId, {
    required DateTime endedAtUtc,
    required int lastSegmentIndex,
  });
  Future<void> updateSegment(QueuedSegment segment);
  Future<void> removeVerifiedSegment(QueuedSegment segment);
  Future<void> markCompletionSent(String sessionId);
}

/// Reads a queued artifact back for upload. The phone resolves a reference to a file; the browser
/// resolves it to bytes it is holding, because a page has no durable filesystem.
abstract interface class SegmentContentStore {
  Future<int> length(String reference);
  Stream<List<int>> read(String reference);
}

abstract interface class CaptureBackend {
  Future<void> ensureSession(QueuedSession session);
  Future<void> uploadSegment(QueuedSegment segment);
  Future<String> segmentState(QueuedSegment segment);
  Future<void> completeSession(QueuedSession session);
}
