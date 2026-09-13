package br.com.greenv.videoapi.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

/**
 * What a caller is asking for when it lists readings.
 *
 * <p>Separate from {@link CaptureSessionQuery} because the questions differ. That one filters
 * captures by what they are; this one filters readings by what they found, and it is the query a
 * dashboard re-runs on every click.
 *
 * <p>The day filter arrives as two instants rather than a date. A day is a local idea and this
 * service has no business guessing whose. The browser knows its own zone, turns "11 September"
 * into the two moments that bound it there, and the API compares instants — which is the only
 * thing a {@code timestamptz} column can compare honestly.
 *
 * @param sort never null; an unknown name falls back to the default order
 * @param level 0 to 3, where 0 gathers everything that was not classified; null means every level
 * @param capturedFrom inclusive lower bound on capture time
 * @param capturedTo exclusive upper bound, so two adjacent days never share a row
 * @param search matched against the resolved place, ignoring case and accents; null means none
 * @param limit page size, bounded by {@link #MAXIMUM_LIMIT}
 * @param offset rows to skip; the ordering carries a tie-break so paging is stable
 */
public record MeasurementQuery(
        MeasurementSort sort,
        Integer level,
        Instant capturedFrom,
        Instant capturedTo,
        String search,
        int limit,
        int offset) {

    /** A screenful. Smaller than the session default because each row here carries a map and photos. */
    public static final int DEFAULT_LIMIT = 25;

    public static final int MAXIMUM_LIMIT = 200;

    public MeasurementQuery {
        sort = sort == null ? MeasurementSort.HEIGHT_DESC : sort;
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAXIMUM_LIMIT);
        offset = Math.max(offset, 0);
        level = level == null || level < 0 || level > 3 ? null : level;
        search = foldAccents(search);
    }

    /**
     * Lower case with the accents taken off, because nobody types them into a search box.
     *
     * <p>Half the street names in the register carry one — Paraíso, Ipiranga do Norte, Cônego
     * Vicente — and a reader searching for "paraiso" means the street, not a different one. The
     * column is folded the same way in the query, so both sides meet without accents rather than
     * one side hoping the other guessed right.
     */
    private static String foldAccents(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    public static MeasurementQuery tallestFirst() {
        return new MeasurementQuery(MeasurementSort.HEIGHT_DESC, null, null, null, null, DEFAULT_LIMIT, 0);
    }

    /**
     * The same filters with no window, for the counters above the list.
     *
     * <p>The four cards and the level chips must count the whole filtered set, not the page. A
     * page-local count beside a paged list is the same defect the browser-side sort had: it looks
     * like an answer about the data and is an answer about the request.
     */
    public MeasurementQuery withoutPaging() {
        return new MeasurementQuery(sort, level, capturedFrom, capturedTo, search, MAXIMUM_LIMIT, 0);
    }

    /** The counters answer for every level, so the level filter itself is dropped. */
    public MeasurementQuery withoutLevel() {
        return new MeasurementQuery(sort, null, capturedFrom, capturedTo, search, limit, offset);
    }
}
