package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.MeasurementQuery;
import br.com.greenv.videoapi.domain.MeasurementSummary;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.FrameReadings;
import br.com.greenv.videoapi.domain.SampledFrame;
import br.com.greenv.videoapi.domain.SegmentQuery;
import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Inbound application operations for segmented mobile captures. */
public interface CaptureSessionUseCase {

    CaptureSessionDocument create(
            UUID requestedSessionId, String deviceId, Instant startedAt, String rodovia, String sentido);

    CaptureSessionSummary getSession(UUID sessionId);

    /**
     * One page of sessions, newest first.
     *
     * <p>Until this existed a caller had to already hold a session id to read anything at all, so
     * nothing could open on a list of what had been captured.
     */
    Page<CaptureSessionSummary> listSessions(CaptureSessionQuery query);

    /** One page of a session's segments, in capture order. Throws when the session is unknown. */
    Page<CaptureSegmentDocument> listSegments(SegmentQuery query);

    /** How many of a session's segments fall in each level, for the filter above the list. */
    MeasurementSummary summariseSegments(UUID sessionId);

    /** One page of readings across every session, ordered and filtered by {@code query}. */
    Page<CaptureSegmentDocument> listMeasurements(MeasurementQuery query);

    /** How many readings the same filter selects, by level, and the tallest among them. */
    MeasurementSummary summariseMeasurements(MeasurementQuery query);

    /** The session drawn as GeoJSON: the camera path and the band the measurement covered. */
    byte[] track(UUID sessionId);

    /** The frames this segment published, with a camera position for each where it is known. */
    List<SampledFrame> frames(UUID sessionId, int segmentIndex);

    /**
     * What one photograph contributed to the measurement.
     *
     * <p>Verge Studio measures a cell by letting every frame that saw it vote. This
     * answers, for one frame, which cells it voted in and what it said, which is the
     * difference between "this stretch is 3.18 m" and "this photograph is why".
     */
    FrameReadings frameReadings(UUID sessionId, int segmentIndex, String fileName);

    /**
     * Derives and stores one segment's frame readings from its assessment.
     *
     * <p>Exists for the backfill, which fills in segments measured before the rows did.
     * Recording a measurement already does this on its own.
     */
    void deriveFrameReadingsFor(UUID sessionId, int segmentIndex);

    /** One published JPEG, by name. Refuses a name the segment's manifest does not list. */
    byte[] frame(UUID sessionId, int segmentIndex, String fileName);

    CaptureSegmentDocument getSegment(UUID sessionId, int segmentIndex);

    CaptureSegmentDocument uploadVideo(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            String expectedSha256,
            InputStream input);

    CaptureSegmentDocument uploadTelemetry(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            String expectedSha256,
            InputStream input);

    CaptureSegmentDocument completeSegment(UUID sessionId, int segmentIndex);

    CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt);

    byte[] manifest(UUID sessionId, int segmentIndex);

    /** The packet worker 2 wrote for this segment, byte for byte. */
    byte[] measurement(UUID sessionId, int segmentIndex);

    /**
     * One file of the reconstruction this segment was measured from, opened for streaming.
     *
     * <p>`scene.glb` is the mesh and `result.npz` the depth arrays the point cloud is rebuilt
     * from; anything else is refused rather than turned into a key. Tens of megabytes each, so
     * the caller gets a stream and closes it. See {@link CaptureObjectStorage#open}.
     */
    CaptureObjectStorage.ObjectContent depthArtifact(UUID sessionId, int segmentIndex, String fileName);

    /** Called when worker 2 announces that it measured a segment. */
    void recordMeasurement(SegmentMeasurementAnnouncement announcement);

    int segmentSeconds();
}
