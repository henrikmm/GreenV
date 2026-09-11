package br.com.greenv.videoapi.domain;

/**
 * What a caller is asking for when it lists capture sessions.
 *
 * <p>A record rather than a string of parameters so the filter can grow without every caller
 * changing, and so the adapter has one place to decide which clauses a query needs. Every filter
 * is optional; all-null means "the most recent page of everything".
 *
 * @param state {@code recording}, {@code processing} or {@code ready}
 * @param rodovia exact match, already upper-cased by the time a session is stored
 * @param sentido one of the four cardinal values
 * @param measuredOnly only sessions with at least one measured segment
 * @param limit page size, bounded by {@link #MAXIMUM_LIMIT}
 * @param offset rows to skip; ordering is deterministic so paging is stable
 */
public record CaptureSessionQuery(
        String state, String rodovia, Sentido sentido, boolean measuredOnly, int limit, int offset) {

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
    }

    public static CaptureSessionQuery recent() {
        return new CaptureSessionQuery(null, null, null, false, DEFAULT_LIMIT, 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
