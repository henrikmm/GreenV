package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.FrameReadings;
import java.util.List;

/**
 * What one photograph measured.
 *
 * <p>Heights are {@code extent95}, above each cell's own local ground, which is the estimator
 * the rest of the API quotes. The plane-relative figures travel alongside so the pedestal stays
 * visible as the difference, and are null on a cell that never established its own ground.
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
        int evidenceForCells,
        List<Cell> cells) {

    public record Cell(
            double alongRoadM,
            double distanceFromRoadM,
            Double voteExtent95M,
            Double cellExtent95M,
            Double voteH95M,
            Double cellH95M,
            long sampleCount,
            String cellStatus) {}

    public static FrameReadingsResponse from(FrameReadings readings) {
        return new FrameReadingsResponse(
                1,
                readings.canonicalFrame(),
                readings.cellsVoted(),
                readings.sampleCount(),
                readings.extent95MedianM(),
                readings.extent95MaxM(),
                readings.largestDisagreementM(),
                readings.evidenceForCells(),
                readings.cells().stream()
                        .map(cell -> new Cell(
                                cell.alongRoadM(),
                                cell.distanceFromRoadM(),
                                cell.voteExtent95M(),
                                cell.cellExtent95M(),
                                cell.voteH95M(),
                                cell.cellH95M(),
                                cell.sampleCount(),
                                cell.cellStatus()))
                        .toList());
    }
}
