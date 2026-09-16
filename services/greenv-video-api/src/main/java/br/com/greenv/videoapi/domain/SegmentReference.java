package br.com.greenv.videoapi.domain;

import java.util.UUID;

/**
 * One measured stretch, named the way every other table names it.
 *
 * <p>A segment has no id of its own — {@code (sessionId, segmentIndex)} is its key throughout the
 * pipeline, from the object storage prefix to the queue message — so an order points at the pair.
 *
 * @param windowIndex which window of that segment the order covers, or null for the whole segment.
 *     A segment is now cut into windows of about 25 m and each is measured on its own, so the
 *     stretch a crew is sent to is a window; null is a segment measured whole, which is every
 *     order opened before windows existed
 */
public record SegmentReference(UUID sessionId, int segmentIndex, Integer windowIndex) {

    /** The whole segment, which is what every target meant until windows existed. */
    public static SegmentReference ofSegment(UUID sessionId, int segmentIndex) {
        return new SegmentReference(sessionId, segmentIndex, null);
    }
}
