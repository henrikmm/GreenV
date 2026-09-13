import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:greenv_capture/src/domain/capture_models.dart';
import 'package:greenv_capture/src/domain/operations_models.dart';

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

  /// Drops a segment whose bytes the API has accepted, and the artifacts behind it.
  ///
  /// Delivery is the end of the queue's job. What the worker later makes of the segment is the
  /// worker's business: the phone cannot act on it, and waiting for it would keep a finished
  /// capture on screen and the device's storage occupied for as long as a queue is backed up.
  Future<void> removeDeliveredSegment(QueuedSegment segment);
  Future<void> markCompletionSent(String sessionId);

  /// Forgets a session that has nothing left to send, so later syncs stop re-announcing it.
  Future<void> removeCompletedSession(String sessionId);
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

  /// Puts the video and the telemetry, then completes the segment. Returning normally means the
  /// API holds both artifacts and has accepted them - which is as far as this client follows a
  /// segment.
  Future<void> uploadSegment(QueuedSegment segment);
  Future<void> completeSession(QueuedSession session);
}

/// The read side, and the one write a field worker makes from the phone.
///
/// Kept apart from [CaptureBackend] on purpose. Capture must never depend on this: a phone with
/// no signal still records and queues, and a screen that cannot list stretches is a screen that
/// says so, not a capture that stops. Everything here is a question about what was already
/// uploaded, plus opening an order against it.
abstract interface class OperationsGateway {
  /// The counters over every measured stretch: how many at each level, and the tallest.
  Future<ReadingsSummary> readingsSummary();

  /// One page of stretches, tallest first, optionally at one level only.
  Future<PageOf<MeasuredStretch>> stretches({
    int? level,
    int limit = 25,
    int offset = 0,
  });

  /// The most recent capture sessions, newest first.
  Future<PageOf<CaptureSessionSummary>> sessions({int limit = 10});

  Future<List<Team>> teams();

  Future<PageOf<ServiceOrder>> orders({int limit = 50});

  /// Every measured stretch of one session, for the map opened from that session.
  ///
  /// Not the readings feed with a filter: that one is ranked by height across every session and
  /// has no session parameter. This reads the session's own segments, which is the route that
  /// knows what belongs to it.
  Future<List<MeasuredStretch>> sessionStretches(String sessionId);

  /// The photographs one segment published, in capture order.
  Future<List<SampledFrame>> frames(String sessionId, int segmentIndex);

  /// What one photograph contributed to the measurement. Null when the packet kept no detail.
  Future<FrameReadings?> frameReadings(
    String sessionId,
    int segmentIndex,
    String fileName,
  );

  /// The bytes of one photograph. The route is behind the same bearer as everything else, so a
  /// plain image widget cannot fetch it and the gateway hands back the bytes instead.
  Future<Uint8List> frameImage(String imageUrl);

  /// Opens an order. The API derives the area, the level and the position from the targets.
  Future<ServiceOrder> openOrder(OrderDraft draft);
}
