package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What worker 2 says when it has measured a segment.
 *
 * <p>The worker publishes its whole {@code measurement-result-v1.json} envelope; this is the part
 * the control plane acts on. Everything else — the height grid, the per-frame positions, the depth
 * provenance — stays in the packet in object storage, which is where a reader that wants it should
 * go. Copying it into the database would duplicate a document that is already addressable.
 */
public record SegmentMeasurementAnnouncement(
        UUID sessionId, int segmentIndex, String runId, boolean mock, Instant measuredAt) {

    public boolean isUsable() {
        return sessionId != null && segmentIndex >= 0 && measuredAt != null;
    }
}
