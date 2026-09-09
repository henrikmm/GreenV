package br.com.greenv.frameextractor.domain;

/**
 * One published JPEG.
 *
 * <p>{@code timestampSeconds} used to be synthesised as {@code index / effectiveFps} — a position on
 * the requested grid rather than the frame's own presentation time — so nothing could key a JPEG
 * back to its row in {@code frame-metadata-v2.json}. On the grouped path it is the real timestamp
 * and {@code sourceFrameIndex} names the encoded frame outright.
 *
 * @param sourceFrameIndex encoded-frame index this came from, or -1 on the uniform fallback where
 *     the rate filter resamples and the mapping is nominal
 * @param distanceMeters how far the camera had travelled when this frame was taken
 * @param groupIndex the stretch this frame reconstructs, or -1 when frames were not grouped
 */
public record FrameRecord(
        int index,
        String fileName,
        double timestampSeconds,
        long sizeBytes,
        String sha256,
        int sourceFrameIndex,
        double distanceMeters,
        int groupIndex) {
}
