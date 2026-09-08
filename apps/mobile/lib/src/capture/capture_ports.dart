import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';

abstract interface class IdentifierGenerator {
  String next();
}

abstract interface class SegmentRecorder {
  /// Frames per second the camera settled on, or null where the platform will not say.
  ///
  /// Only the browser reports this: AVFoundation and CameraX accept a requested rate and never
  /// disclose what they granted. Where it is null, the worker's measured `nativeFps` in the segment
  /// manifest is the only answer.
  double? get capturedFrameRate => null;

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

/// Holds the refresh token between runs, so signing in survives closing the app.
///
/// Only the refresh token is kept. The access token lives fifteen minutes and is cheap to mint
/// again, so writing it down would add exposure and buy nothing.
abstract interface class SessionStore {
  Future<String?> read();
  Future<void> write(String refreshToken);
  Future<void> clear();
}

/// Supplies the credential every request carries.
///
/// The capture client is a public client: anything compiled into the binary is extractable, so a
/// long-lived secret baked in with `--dart-define` is not a secret. The credential therefore comes
/// from the person signing in, not from the build.
abstract interface class AuthTokenProvider {
  /// The value for the `Authorization: Bearer` header. Empty means send no header at all.
  Future<String> accessToken();

  /// Called once after a 401. Returns whether a new token was obtained and the call is worth
  /// retrying.
  Future<bool> refresh();
}

abstract interface class CaptureBackend {
  Future<void> ensureSession(QueuedSession session);
  Future<void> uploadSegment(QueuedSegment segment);
  Future<String> segmentState(QueuedSegment segment);
  Future<void> completeSession(QueuedSession session);
}
