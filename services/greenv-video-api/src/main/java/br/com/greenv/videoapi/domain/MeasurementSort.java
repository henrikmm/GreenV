package br.com.greenv.videoapi.domain;

/**
 * How a page of readings is ordered.
 *
 * <p>An enum rather than a sort string from the caller, because the value ends up in an ORDER BY
 * and a parameter placeholder cannot stand there. A closed set is the difference between a sort
 * option and an injection point.
 *
 * <p>Every order carries the primary key behind it. Two segments measured at the same height are
 * otherwise free to swap places between one request and the next, and a row then appears twice or
 * not at all to somebody paging through the list.
 *
 * <p>Nulls go last in both directions, which is a decision rather than a default. A segment nobody
 * could measure is not a short one; sorted as zero it would sit among the trimmed verges, and the
 * list exists to put the worst first.
 */
public enum MeasurementSort {
    /** The tallest grass first. What the list opens on, because it is the order that decides work. */
    HEIGHT_DESC("measurement_extent95_p95_m DESC NULLS LAST"),
    HEIGHT_ASC("measurement_extent95_p95_m ASC NULLS LAST"),

    /** The day of the outing, which is how a person remembers a capture. */
    CAPTURED_DESC("captured_at DESC"),
    CAPTURED_ASC("captured_at ASC"),

    /** When the measurement ran, which is freshness rather than severity. */
    MEASURED_DESC("measured_at DESC NULLS LAST");

    private static final String TIE_BREAK = ", session_id, segment_index";

    private final String sortKey;

    MeasurementSort(String sortKey) {
        this.sortKey = sortKey;
    }

    /** The ORDER BY body, tie-break included. Never interpolate anything else into a query. */
    public String orderBy() {
        return sortKey + TIE_BREAK;
    }

    /** An unknown or missing name is the default order, not an error: a bad link still lists. */
    public static MeasurementSort of(String name) {
        if (name == null || name.isBlank()) {
            return HEIGHT_DESC;
        }
        for (MeasurementSort candidate : values()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        return HEIGHT_DESC;
    }
}
