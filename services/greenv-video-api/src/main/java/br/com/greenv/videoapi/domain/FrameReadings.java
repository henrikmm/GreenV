package br.com.greenv.videoapi.domain;

/**
 * What one photograph contributed to the measurement.
 *
 * <p>Verge Studio measures a cell by letting every frame that saw it vote, then taking a
 * percentile across the votes. `assessment.json` keeps those votes, so a single frame can be
 * asked what it said and how far it was from the answer the cell settled on. That is the
 * difference between "this stretch is 3.18 m" and "this photograph is why".
 *
 * <p>The height is {@code extent95}, above each cell's own local ground, because that is the
 * estimator the rest of the system quotes. A vote records {@code h95} above the fitted plane, so
 * the conversion subtracts the cell's {@code localGroundM}. A cell that never established its
 * own ground contributes nothing here rather than silently falling back to the plane, which
 * reads higher.
 *
 * <p>No per-cell breakdown. The screen shows the aggregate, and carrying a row per cell per
 * frame would be two thousand rows for a ten-second segment to answer a question nobody has
 * asked. The assessment is still in storage for the day someone does.
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
        int evidenceForCells) {

    public static FrameReadings empty(int canonicalFrame) {
        return new FrameReadings(canonicalFrame, 0, 0, null, null, null, 0);
    }
}
