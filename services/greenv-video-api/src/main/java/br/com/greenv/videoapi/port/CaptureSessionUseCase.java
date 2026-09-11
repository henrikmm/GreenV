package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SampledFrame;
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

    /** Every segment of one session, in capture order. Throws when the session is unknown. */
    List<CaptureSegmentDocument> listSegments(UUID sessionId);

    /** Measured segments across every session, newest first: the map's feed. */
    Page<CaptureSegmentDocument> listMeasurements(CaptureSessionQuery query);

    /** The session drawn as GeoJSON: the camera path and the band the measurement covered. */
    byte[] track(UUID sessionId);

    /** The frames this segment published, with a camera position for each where it is known. */
    List<SampledFrame> frames(UUID sessionId, int segmentIndex);

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

    /** Called when worker 2 announces that it measured a segment. */
    void recordMeasurement(SegmentMeasurementAnnouncement announcement);

    int segmentSeconds();
}
