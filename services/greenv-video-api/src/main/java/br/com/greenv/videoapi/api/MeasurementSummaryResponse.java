package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.MeasurementSummary;
import java.util.Map;

/**
 * The counters that sit above a paged list.
 *
 * <p>Its own route rather than a field on the page, because the answer is the same for every page
 * of one filter: a client fetches it when the filter changes and not when the reader scrolls.
 *
 * @param countsByLevel keyed by level as text, because that is what JSON object keys are
 */
public record MeasurementSummaryResponse(
        int schemaVersion,
        long total,
        Map<String, Long> countsByLevel,
        Double tallestExtent95M,
        Map<String, Long> countsByLocationQuality) {

    public static MeasurementSummaryResponse from(MeasurementSummary summary) {
        return new MeasurementSummaryResponse(
                1,
                summary.total(),
                summary.countsByLevel().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)),
                summary.tallestM(),
                summary.countsByLocationQuality());
    }
}
