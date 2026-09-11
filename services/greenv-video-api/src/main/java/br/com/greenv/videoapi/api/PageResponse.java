package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.Page;
import java.util.List;
import java.util.function.Function;

/**
 * One page of anything, with enough context that a caller can ask for the next one.
 *
 * <p>{@code hasMore} is carried rather than left to be worked out, because "did I reach the end"
 * computed from three other numbers is computed differently by every client.
 */
public record PageResponse<T>(int schemaVersion, List<T> items, long total, int limit, int offset, boolean hasMore) {

    public static <D, R> PageResponse<R> from(Page<D> page, Function<D, R> mapper) {
        return new PageResponse<>(
                1, page.items().stream().map(mapper).toList(), page.total(), page.limit(), page.offset(), page.hasMore());
    }
}
