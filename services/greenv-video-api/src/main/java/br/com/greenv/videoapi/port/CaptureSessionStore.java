package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistent control-plane state. Implementations may use any transactional database. */
public interface CaptureSessionStore {

    CaptureSessionDocument createSession(CaptureSessionDocument session);

    CaptureSessionDocument getSession(UUID sessionId);

    Optional<CaptureSessionDocument> findSession(UUID sessionId);

    Optional<CaptureSegmentDocument> findSegment(UUID sessionId, int segmentIndex);

    CaptureSegmentDocument getSegment(UUID sessionId, int segmentIndex);

    CaptureSegmentDocument ensureSegment(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            Instant now);

    CaptureSegmentDocument recordVideo(
            UUID sessionId, int segmentIndex, String objectKey, String sha256, long bytes, Instant now);

    CaptureSegmentDocument recordTelemetry(
            UUID sessionId, int segmentIndex, String objectKey, String sha256, long bytes, Instant now);

    CaptureSegmentDocument markQueued(UUID sessionId, int segmentIndex, Instant now);

    CaptureSegmentDocument markQueueFailed(
            UUID sessionId, int segmentIndex, String errorCode, String errorMessage, Instant now);

    /**
     * Records that worker 2 measured this segment. Idempotent by construction: the worker republishes
     * the same result when it redelivers, and the row simply reads the same afterwards.
     */
    CaptureSegmentDocument recordMeasurement(
            UUID sessionId,
            int segmentIndex,
            String objectKey,
            String runId,
            boolean mock,
            Instant measuredAt,
            Instant now);

    CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt, Instant now);

    long segmentCount(UUID sessionId);

    long readySegmentCount(UUID sessionId);
}
