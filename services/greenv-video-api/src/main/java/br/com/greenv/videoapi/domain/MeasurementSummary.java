package br.com.greenv.videoapi.domain;

import java.util.Map;

/**
 * The counters above a paged list.
 *
 * <p>They exist because a page cannot answer them. "Three above thirty centimetres" has to mean
 * three in everything the filter selected, not three on the screen, and once the list is paged the
 * browser no longer holds the rest. One aggregate query answers all of it.
 *
 * @param total readings matching the filter, every level included
 * @param countsByLevel how many fell in each of 0 to 3; a level with none is present as zero
 * @param tallestM the highest reading in the filtered set, or null when none was measurable
 */
public record MeasurementSummary(long total, Map<Integer, Long> countsByLevel, Double tallestM) {

    public MeasurementSummary {
        countsByLevel = Map.copyOf(countsByLevel);
    }

    public static MeasurementSummary empty() {
        return new MeasurementSummary(0, Map.of(0, 0L, 1, 0L, 2, 0L, 3, 0L), null);
    }
}
