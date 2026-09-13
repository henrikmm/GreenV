package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.MeasurementProjection;
import br.com.greenv.videoapi.domain.MeasurementQuery;
import br.com.greenv.videoapi.domain.MeasurementSummary;
import br.com.greenv.videoapi.domain.FrameReadings;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SegmentPlace;
import br.com.greenv.videoapi.domain.SegmentQuery;
import java.time.Instant;
import java.util.List;
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
     *
     * @param projection what a map can draw, derived from the packet; {@link
     *     MeasurementProjection#EMPTY} when it could not be read, so a measurement is still
     *     recorded when only its summary is missing
     */
    CaptureSegmentDocument recordMeasurement(
            UUID sessionId,
            int segmentIndex,
            String objectKey,
            String runId,
            boolean mock,
            Instant measuredAt,
            MeasurementProjection projection,
            Instant now);

    CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt, Instant now);

    /** One page of sessions, newest first, filtered by {@code query}. */
    Page<CaptureSessionSummary> findSessions(CaptureSessionQuery query);

    /** Every segment of one session, in capture order. Callers that need the whole sequence. */
    List<CaptureSegmentDocument> findSegments(UUID sessionId);

    /** One page of a session's segments, in capture order. */
    Page<CaptureSegmentDocument> findSegments(SegmentQuery query);

    /** How many of a session's segments fall in each level, so a filter can show its own size. */
    MeasurementSummary summariseSegments(UUID sessionId);

    /** One page of readings across every session, ordered and filtered by {@code query}. */
    Page<CaptureSegmentDocument> findMeasurements(MeasurementQuery query);

    /** The counters for the whole filtered set, which a page of it cannot answer. */
    MeasurementSummary summariseMeasurements(MeasurementQuery query);

    long segmentCount(UUID sessionId);

    long readySegmentCount(UUID sessionId);

    long measuredSegmentCount(UUID sessionId);

    /**
     * Measured stretches that carry a position and were never asked about.
     *
     * <p>Ordered newest first, so a fresh measurement gets its street name before an old one that
     * nobody is looking at.
     */
    List<CaptureSegmentDocument> findSegmentsAwaitingPlace(int limit);

    /** Caches the place on the row. A row is written even when nothing was found. */
    void recordPlace(UUID sessionId, int segmentIndex, SegmentPlace place);

    /**
     * Replaces every frame reading of a segment, in one transaction.
     *
     * <p>Replace and not merge: the readings are derived wholesale from one assessment, and
     * a partial overwrite would leave rows from a previous run beside rows from this one.
     */
    void replaceFrameReadings(UUID sessionId, int segmentIndex, List<FrameReadings> readings);

    Optional<FrameReadings> findFrameReadings(UUID sessionId, int segmentIndex, int canonicalFrame);

    /** Measured segments whose frame readings were never derived. Newest measurement first. */
    List<CaptureSegmentDocument> findSegmentsAwaitingFrameReadings(int limit);
}
