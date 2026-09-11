package br.com.greenv.videoapi.domain;

import java.util.List;

/**
 * One page of rows, with the total so a caller can tell "no more" from "ask again".
 *
 * <p>Lives in {@code domain} rather than beside the HTTP records because an inbound port may not
 * name a type from {@code api} — the architecture test enforces it, and the reason is that a use
 * case describing itself in HTTP terms is a use case only HTTP can call.
 */
public record Page<T>(List<T> items, long total, int limit, int offset) {

    public Page {
        items = List.copyOf(items);
    }

    public static <T> Page<T> of(List<T> items, long total, CaptureSessionQuery query) {
        return new Page<>(items, total, query.limit(), query.offset());
    }

    public boolean hasMore() {
        return offset + items.size() < total;
    }
}
