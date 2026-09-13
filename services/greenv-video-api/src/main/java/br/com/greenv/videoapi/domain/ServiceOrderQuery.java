package br.com.greenv.videoapi.domain;

import java.util.UUID;

/**
 * What a caller is asking for when it lists service orders.
 *
 * <p>Same shape and same ceiling as {@link CaptureSessionQuery}, for the same reason: a page is a
 * screen, not an export.
 *
 * @param status only orders in this state
 * @param priority only orders at this priority
 * @param teamId only orders assigned to this team
 * @param search matched against the reference and the notes, case-insensitively
 */
public record ServiceOrderQuery(
        ServiceOrderStatus status,
        ServiceOrderPriority priority,
        UUID teamId,
        String search,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 50;

    public static final int MAXIMUM_LIMIT = 200;

    public ServiceOrderQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAXIMUM_LIMIT);
        offset = Math.max(offset, 0);
        search = search == null || search.isBlank() ? null : search.trim();
    }

    public static ServiceOrderQuery recent() {
        return new ServiceOrderQuery(null, null, null, null, DEFAULT_LIMIT, 0);
    }
}
