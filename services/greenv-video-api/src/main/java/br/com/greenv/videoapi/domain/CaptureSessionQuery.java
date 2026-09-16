package br.com.greenv.videoapi.domain;

import java.time.Instant;

/**
 * What a caller is asking for when it lists capture sessions.
 *
 * <p>A record rather than a string of parameters so the filter can grow without every caller
 * changing, and so the adapter has one place to decide which clauses a query needs. Every filter
 * is optional; all-null means "the most recent page of everything".
 *
 * <p>The day filter arrives as two instants rather than a date, for the reason {@link
 * MeasurementQuery} gives: a day is a local idea and this service has no business guessing whose.
 * The browser knows its own zone and sends the two moments that bound the day there.
 *
 * @param state {@code recording}, {@code processing} or {@code ready}
 * @param rodovia exact match, already upper-cased by the time a session is stored
 * @param sentido one of the four cardinal values
 * @param measuredOnly only sessions with at least one measured segment
 * @param capturedFrom inclusive lower bound on when the session started
 * @param capturedTo exclusive upper bound, so two adjacent days never share a session
 * @param sort never null; an unknown name falls back to the default order
 * @param limit page size, bounded by {@link #MAXIMUM_LIMIT}
 * @param offset rows to skip; the ordering carries a tie-break so paging is stable
 */
public record CaptureSessionQuery(
        String state,
        String rodovia,
        Sentido sentido,
        boolean measuredOnly,
        Instant capturedFrom,
        Instant capturedTo,
        CaptureSessionSort sort,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 50;

    /**
     * A page is a screen, not an export. Without a ceiling one request can ask the database for
     * every session ever recorded and the API for the memory to hold them.
     */
    public static final int MAXIMUM_LIMIT = 200;

    public CaptureSessionQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAXIMUM_LIMIT);
        offset = Math.max(offset, 0);
        state = blankToNull(state);
        rodovia = blankToNull(rodovia);
        sort = sort == null ? CaptureSessionSort.STARTED_DESC : sort;
    }

    public static CaptureSessionQuery recent() {
        return new CaptureSessionQuery(
                null, null, null, false, null, null, CaptureSessionSort.STARTED_DESC, DEFAULT_LIMIT, 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
