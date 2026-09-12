package br.com.greenv.videoapi.domain;

import java.util.List;

/**
 * What one photograph contributed to the measurement.
 *
 * <p>Verge Studio measures a cell by letting every frame that saw it vote, then taking a
 * percentile across the votes. `assessment.json` keeps those votes, so a single frame can be
 * asked what it said and how far it was from the answer the cell settled on. That is the
 * difference between "this stretch is 3.18 m" and "this photograph is why".
 *
 * <p>The height reported here is {@code extent95}, above the cell's own local ground, because
 * that is the estimator the rest of the system quotes. A vote records {@code h95} above the
 * fitted plane, so the conversion subtracts the cell's {@code localGroundM}. A cell that never
 * established its own ground has no local reading at all, and the field is null rather than
 * silently falling back to the plane, which reads higher.
 *
 * @param canonicalFrame the number in the JPEG's file name
 * @param cellsVoted cells this frame reached the voxel floor in
 * @param evidenceForCells cells that named this frame as one to look at
 * @param largestDisagreementM the widest gap between this frame's h95 and the cell's own
 */
public record FrameReadings(
        int canonicalFrame,
        int cellsVoted,
        long sampleCount,
        Double extent95MedianM,
        Double extent95MaxM,
        Double largestDisagreementM,
        int evidenceForCells,
        List<CellVote> cells) {

    public static final FrameReadings EMPTY =
            new FrameReadings(0, 0, 0, null, null, null, 0, List.of());

    public FrameReadings {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    /**
     * One cell as this frame saw it.
     *
     * @param alongRoadM where the cell sits along the stretch, in road-local metres
     * @param distanceFromRoadM how far from the track, with the side folded away
     * @param voteExtent95M what this frame said, above the cell's own ground
     * @param cellExtent95M what the cell settled on across every frame
     * @param cellStatus {@code measured} or {@code insufficient-support}
     */
    public record CellVote(
            double alongRoadM,
            double distanceFromRoadM,
            Double voteExtent95M,
            Double cellExtent95M,
            Double voteH95M,
            Double cellH95M,
            long sampleCount,
            String cellStatus) {}
}
