package br.com.greenv.videoapi.domain;

import java.time.Instant;

/**
 * Where a measured stretch is, said in words.
 *
 * <p>Derived from the stretch's own track centre and cached on the row. Every field is nullable
 * because every one of them can legitimately be absent: a point inside a block has no street, a
 * verge has no house number, and a capture fourteen kilometres from the reference road has no
 * kilometre marker worth quoting.
 *
 * @param label the street, or the neighbourhood when no street is mapped there
 * @param detail the neighbourhood and the city, for the line under the label
 * @param houseNumber approximate: the nearest addressable point, which on a verge is the
 *     building across the road
 * @param road the reference road, when the stretch is close enough to be on it
 * @param km the nearest kilometre marker; the markers average 1066 m apart, so this names a
 *     stretch of road and is never a position
 * @param kmOffsetMetres how far that marker was, so a reader can see how loose the km is
 */
public record SegmentPlace(
        String label,
        String detail,
        String houseNumber,
        String road,
        Integer km,
        Double kmOffsetMetres,
        String source,
        Instant resolvedAt) {

    /** Tried and found nothing. Recorded so the backfill does not ask again every minute. */
    public static SegmentPlace unresolved(Instant resolvedAt) {
        return new SegmentPlace(null, null, null, null, null, null, "none", resolvedAt);
    }

    public boolean isEmpty() {
        return label == null && road == null;
    }
}
