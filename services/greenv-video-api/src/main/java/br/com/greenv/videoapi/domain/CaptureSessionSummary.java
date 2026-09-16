package br.com.greenv.videoapi.domain;

/**
 * A capture session together with the counters its clients need.
 *
 * <p>{@code measuredSegmentCount} is the one a list opens on: "four segments, two measured" is
 * the difference between a session still working and a session finished, and computing it per row
 * from a second query would be one round trip per session on every page.
 *
 * <p>{@code readings} is the same session counted at the scale a crew is sent to. A segment is an
 * upload; a reading is the 25 m stretch that was measured, and the sessions screen ranks by how
 * many of those are overdue.
 *
 * <p>{@code place} comes with the row for the same reason the counters do: a paged list cannot
 * work out where a session was from readings it did not load, and labelling a row "no position"
 * because the browser happens to hold no reading of it would be a lie about the data.
 */
public record CaptureSessionSummary(
        CaptureSessionDocument session,
        long segmentCount,
        long readySegmentCount,
        long measuredSegmentCount,
        SessionReadingCounts readings,
        SessionPlace place) {
}
