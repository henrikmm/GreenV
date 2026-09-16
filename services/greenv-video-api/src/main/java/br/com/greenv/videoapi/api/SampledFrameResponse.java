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
 *
 * <p>{@code windowIndex} says which measured stretch the photograph belongs to. The list is a
 * segment's -- ten seconds of video, some hundreds of metres of road -- and a screen showing one
 * 25 m stretch has to know which of them are its own. Null when the segment was measured whole.
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
        Integer windowIndex,
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
                frame.windowIndex(),
                baseUrl + "/v2/capture-sessions/" + sessionId + "/segments/" + segmentIndex
                        + "/frames/" + frame.fileName());
    }
}
