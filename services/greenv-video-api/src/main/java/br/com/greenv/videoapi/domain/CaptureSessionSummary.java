package br.com.greenv.videoapi.domain;

/**
 * A capture session together with the segment counters its clients need.
 *
 * <p>{@code measuredSegmentCount} is the one a list opens on: "four segments, two measured" is
 * the difference between a session still working and a session finished, and computing it per row
 * from a second query would be one round trip per session on every page.
 */
public record CaptureSessionSummary(
        CaptureSessionDocument session,
        long segmentCount,
        long readySegmentCount,
        long measuredSegmentCount) {
}
