package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What worker 2 says when it has measured a window of a segment.
 *
 * <p>The worker publishes its whole {@code measurement-result-v1.json} envelope; this is the part
 * the control plane acts on. Everything else — the height grid, the per-frame positions, the depth
 * provenance — stays in the packet in object storage, which is where a reader that wants it should
 * go. Copying it into the database would duplicate a document that is already addressable.
 *
 * @param windowIndex which stretch of the segment was measured, or null when the segment was
 *     measured whole. Null is every packet written before the extractor started cutting a segment
 *     into windows, and any segment whose frames make a single window
 * @param windowStartMeters where the window begins along the segment's camera path, in metres.
 *     Null when the worker did not say, which is not the same as zero
 * @param windowEndMeters where it ends, on the same measure
 */
public record SegmentMeasurementAnnouncement(
        UUID sessionId,
        int segmentIndex,
        Integer windowIndex,
        Double windowStartMeters,
        Double windowEndMeters,
        String runId,
        boolean mock,
        Instant measuredAt) {

    /** A segment measured whole, which is what every announcement was until windows existed. */
    public static SegmentMeasurementAnnouncement ofSegment(
            UUID sessionId, int segmentIndex, String runId, boolean mock, Instant measuredAt) {
        return new SegmentMeasurementAnnouncement(
                sessionId, segmentIndex, null, null, null, runId, mock, measuredAt);
    }

    public boolean isUsable() {
        return sessionId != null && segmentIndex >= 0 && measuredAt != null
                && (windowIndex == null || windowIndex >= 0);
    }
}
