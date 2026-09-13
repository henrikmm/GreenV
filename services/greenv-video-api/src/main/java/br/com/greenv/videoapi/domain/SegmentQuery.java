package br.com.greenv.videoapi.domain;

import java.util.UUID;

/**
 * One page of the segments inside a single session.
 *
 * <p>This route used to answer with every segment a session ever recorded, on the reasoning that
 * a session is finite. It is, but not small: a capture runs in ten-second segments, so an hour in
 * the field is three hundred and sixty of them and an afternoon is over a thousand. Finite is not
 * the same as bounded, and the dashboard was fetching the lot and then asking the API for the
 * frames of each one, a request per segment, before it drew anything.
 *
 * <p>Ordered by {@code segment_index} always. A session is a sequence and reading it out of order
 * would be reading a different thing; there is no sort option here for that reason.
 *
 * @param sessionId the session whose segments are wanted
 * @param level 0 to 3, where 0 gathers everything unclassified; null means every level
 * @param limit page size, bounded by {@link #MAXIMUM_LIMIT}
 * @param offset rows to skip; the primary key orders the page so paging is stable
 */
public record SegmentQuery(UUID sessionId, Integer level, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 25;

    public static final int MAXIMUM_LIMIT = 200;

    public SegmentQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAXIMUM_LIMIT);
        offset = Math.max(offset, 0);
        level = level == null || level < 0 || level > 3 ? null : level;
    }

    public static SegmentQuery first(UUID sessionId) {
        return new SegmentQuery(sessionId, null, DEFAULT_LIMIT, 0);
    }
}
