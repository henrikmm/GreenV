package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.FrameReadings;

/**
 * What one photograph measured.
 *
 * <p>Heights are {@code extent95}, above each cell's own local ground, which is the estimator
 * the rest of the API quotes. Read from a row rather than from the assessment: the answer is
 * derived once, when the measurement is recorded, and never changes after.
 */
public record FrameReadingsResponse(
        int schemaVersion,
        int canonicalFrame,
        int cellsVoted,
        long sampleCount,
        Double extent95MedianM,
        Double extent95MaxM,
        // The widest gap between what this frame said and what its cell settled on. Big means
        // this photograph disagreed with the others that saw the same patch.
        Double largestDisagreementM,
        int evidenceForCells) {

    public static FrameReadingsResponse from(FrameReadings readings) {
        return new FrameReadingsResponse(
                1,
                readings.canonicalFrame(),
                readings.cellsVoted(),
                readings.sampleCount(),
                readings.extent95MedianM(),
                readings.extent95MaxM(),
                readings.largestDisagreementM(),
                readings.evidenceForCells());
    }
}
