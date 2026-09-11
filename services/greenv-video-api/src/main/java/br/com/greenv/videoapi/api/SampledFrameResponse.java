package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.SampledFrame;
import java.time.Instant;
import java.util.UUID;

/**
 * One published frame, and where the camera was.
 *
 * <p>The coordinates are null for a segment that was never measured: nothing joined its images to
 * the telemetry, and a position invented from the session's average would put a photograph
 * somewhere the camera never stood.
 */
public record SampledFrameResponse(
        String fileName,
        int canonicalFrame,
        Long sizeBytes,
        Instant capturedAtUtc,
        Double latitude,
        Double longitude,
        Double horizontalAccuracyMeters,
        String locationQuality,
        String imageUrl) {

    public static SampledFrameResponse from(
            SampledFrame frame, UUID sessionId, int segmentIndex, String baseUrl) {
        return new SampledFrameResponse(
                frame.fileName(),
                frame.canonicalFrame(),
                frame.sizeBytes(),
                frame.capturedAtUtc(),
                frame.latitude(),
                frame.longitude(),
                frame.horizontalAccuracyMeters(),
                frame.locationQuality(),
                baseUrl + "/v2/capture-sessions/" + sessionId + "/segments/" + segmentIndex
                        + "/frames/" + frame.fileName());
    }
}
