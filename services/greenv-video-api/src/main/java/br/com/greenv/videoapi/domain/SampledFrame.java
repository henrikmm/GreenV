package br.com.greenv.videoapi.domain;

import java.time.Instant;

/**
 * One published JPEG, and where the camera was when it was taken.
 *
 * <p>The file names come from the segment manifest, which is what actually proves an image was
 * published. The coordinates come from worker 2's packet, which is where the sampled frames were
 * joined to the telemetry — so an unmeasured segment lists its frames with no position rather than
 * with a guessed one.
 *
 * @param fileName the plain name, {@code frame-0001.jpg}; never a path
 * @param canonicalFrame the integer in that name, which is how a position refers to a frame
 * @param locationQuality {@code good}, {@code degraded}, {@code unavailable}, or null when the
 *     segment was never measured and nothing joined this frame to a fix
 */
public record SampledFrame(
        String fileName,
        int canonicalFrame,
        Long sizeBytes,
        Instant capturedAtUtc,
        Double latitude,
        Double longitude,
        Double horizontalAccuracyMeters,
        String locationQuality) {

    public boolean isLocated() {
        return latitude != null && longitude != null;
    }
}
