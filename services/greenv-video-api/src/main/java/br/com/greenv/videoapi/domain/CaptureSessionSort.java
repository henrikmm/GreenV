package br.com.greenv.videoapi.domain;

/**
 * How a page of sessions is ordered.
 *
 * <p>An enum rather than a sort string from the caller, for the reason {@link MeasurementSort}
 * gives: the value ends up in an ORDER BY, where a parameter placeholder cannot stand, and a
 * closed set is the difference between a sort option and an injection point.
 *
 * <p>Every order ends with the session id, so a page boundary cannot fall between two sessions
 * that compare equal and show one of them twice while somebody scrolls.
 */
public enum CaptureSessionSort {

    /** Newest outing first. What the list opens on, and what it did before it could be asked. */
    STARTED_DESC("started_at DESC"),
    STARTED_ASC("started_at ASC"),

    /**
     * The sessions with the most level-3 readings first, level 2 breaking the tie.
     *
     * <p>Not by the count of readings and not by the tallest one: a single 90 cm window on an
     * otherwise trimmed verge is one crew-hour, and twenty of them is a day's work. The date
     * breaks a tie last, so two equally overdue captures still read newest first.
     */
    CRITICAL_DESC("reading_level_3 DESC, reading_level_2 DESC, started_at DESC");

    /** The session id, which is unique, so an order is total and paging cannot repeat a row. */
    private static final String TIE_BREAK = ", session_id DESC";

    private final String sortKey;

    CaptureSessionSort(String sortKey) {
        this.sortKey = sortKey;
    }

    /** The ORDER BY body, tie-break included. Never interpolate anything else into a query. */
    public String orderBy() {
        return sortKey + TIE_BREAK;
    }

    /** An unknown or missing name is the default order, not an error: a bad link still lists. */
    public static CaptureSessionSort of(String name) {
        if (name == null || name.isBlank()) {
            return STARTED_DESC;
        }
        for (CaptureSessionSort candidate : values()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        return STARTED_DESC;
    }
}
