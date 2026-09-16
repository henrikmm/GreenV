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
import br.com.greenv.videoapi.domain.SessionPlace;
import br.com.greenv.videoapi.domain.SessionReadingCounts;
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

    /**
     * Records that worker 2 measured one window of a segment, and rolls the window set up onto the
     * segment's own projection.
     *
     * <p>The rollup is what keeps the session screen and the per-segment counters working while
     * the unit of measurement moves underneath them: the segment reports the worst window's height
     * and level, the summed cell counts and the latest measurement. Idempotent for the same reason
     * the segment path is — the worker republishes the same result when a delivery is retried.
     */
    CaptureSegmentDocument recordWindowMeasurement(
            UUID sessionId,
            int segmentIndex,
            int windowIndex,
            Double startMeters,
            Double endMeters,
            String objectKey,
            String runId,
            boolean mock,
            Instant measuredAt,
            MeasurementProjection projection,
            Instant now);

    /** One window of one segment, or empty when that window was never measured. */
    Optional<CaptureSegmentDocument> findWindow(UUID sessionId, int segmentIndex, int windowIndex);

    /** Every measured window of one segment, in window order. */
    List<CaptureSegmentDocument> findWindows(UUID sessionId, int segmentIndex);

    /** Every measured window of a whole session, ordered by segment and then by window. */
    List<CaptureSegmentDocument> findWindows(UUID sessionId);

    CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt, Instant now);

    /** One page of sessions, newest first, filtered by {@code query}. */
    Page<CaptureSessionSummary> findSessions(CaptureSessionQuery query);

    /** Every segment of one session, in capture order. Callers that need the whole sequence. */
    List<CaptureSegmentDocument> findSegments(UUID sessionId);

    /** One page of a session's segments, in capture order. */
    Page<CaptureSegmentDocument> findSegments(SegmentQuery query);

    /** How many of a session's segments fall in each level, so a filter can show its own size. */
    MeasurementSummary summariseSegments(UUID sessionId);

    /**
     * One page of readings across every session, ordered and filtered by {@code query}.
     *
     * <p>One row per measured window, plus one row per segment that was measured whole and has no
     * windows. A segment cut into windows never appears on its own: its eight readings are eight
     * rows, which is the point of cutting it.
     */
    Page<CaptureSegmentDocument> findMeasurements(MeasurementQuery query);

    /** The counters for the whole filtered set, which a page of it cannot answer. */
    MeasurementSummary summariseMeasurements(MeasurementQuery query);

    long segmentCount(UUID sessionId);

    long readySegmentCount(UUID sessionId);

    long measuredSegmentCount(UUID sessionId);

    /**
     * How many readings of each level one session holds.
     *
     * <p>Counted over the set {@link #findMeasurements} pages — a window where the segment was
     * cut into windows, the segment itself where it was not — so one session's line and the
     * readings screen behind it report the same thing.
     */
    SessionReadingCounts readingCounts(UUID sessionId);

    /** Where one session was, by the same rule its line in the list answers with. */
    SessionPlace readingPlace(UUID sessionId);

    /**
     * Measured stretches that carry a position and were never asked about — windows included.
     *
     * <p>Ordered newest first, so a fresh measurement gets its street name before an old one that
     * nobody is looking at. A window carries its own coordinate and gets its own name: a 200 m
     * segment crosses streets, and naming all of its windows after the middle one is the
     * distortion this cache exists to avoid.
     */
    List<CaptureSegmentDocument> findSegmentsAwaitingPlace(int limit);

    /**
     * Caches the place on the row. A row is written even when nothing was found.
     *
     * @param windowIndex which window the place belongs to, or null for the segment's own row
     */
    void recordPlace(UUID sessionId, int segmentIndex, Integer windowIndex, SegmentPlace place);

    /**
     * Replaces every frame reading of a segment, in one transaction.
     *
     * <p>Replace and not merge: the readings are derived wholesale from one assessment, and
     * a partial overwrite would leave rows from a previous run beside rows from this one.
     */
    void replaceFrameReadings(UUID sessionId, int segmentIndex, List<FrameReadings> readings);

    /**
     * Writes the readings of one window without disturbing its neighbours'.
     *
     * <p>The readings are keyed by the photograph, and a photograph belongs to exactly one window,
     * so the windows of a segment derive disjoint sets. Replacing wholesale — which is right when
     * one assessment describes the whole segment — would make every window's announcement erase
     * the ones announced before it, and the segment would end up holding only its last window's
     * frames.
     */
    void mergeFrameReadings(UUID sessionId, int segmentIndex, List<FrameReadings> readings);

    Optional<FrameReadings> findFrameReadings(UUID sessionId, int segmentIndex, int canonicalFrame);

    /** Measured segments whose frame readings were never derived. Newest measurement first. */
    List<CaptureSegmentDocument> findSegmentsAwaitingFrameReadings(int limit);
}
