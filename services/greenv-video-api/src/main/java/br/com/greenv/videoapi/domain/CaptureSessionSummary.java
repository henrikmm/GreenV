package br.com.greenv.videoapi.domain;

/** A capture session together with the segment counters needed by its clients. */
public record CaptureSessionSummary(
        CaptureSessionDocument session,
        long segmentCount,
        long readySegmentCount) {
}
