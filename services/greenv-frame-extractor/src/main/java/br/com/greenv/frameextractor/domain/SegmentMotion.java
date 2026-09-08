package br.com.greenv.frameextractor.domain;

/**
 * How far the camera actually travelled during a segment, which is what multi-view geometry needs.
 *
 * <p>Two distances, because they fail differently. {@code pathMeters} follows the route and is what
 * frames are spaced along. {@code netDisplacementMeters} is the straight line from first fix to
 * last, and it is the honest test of whether the camera moved at all: GNSS noise accumulates into a
 * path sum but cancels in a displacement. A parked phone with 5 m fixes reports tens of metres of
 * path and only a few of displacement.
 *
 * @param usableFixes fixes that survived validation; below two, nothing here means anything
 * @param fromSpeed true when the path came from integrating Doppler speed rather than from
 *     differencing positions, which is the more precise of the two
 */
public record SegmentMotion(
        double pathMeters,
        double netDisplacementMeters,
        double medianHorizontalAccuracyMeters,
        int usableFixes,
        boolean fromSpeed) {

    public static final SegmentMotion UNKNOWN = new SegmentMotion(0, 0, 0, 0, false);

    public boolean isKnown() {
        return usableFixes >= 2;
    }

    /**
     * Whether the camera moved far enough for the displacement to be motion rather than noise.
     * The floor scales with the measured accuracy because that is what sets the noise, and never
     * drops below {@code minimumMeters} for an optimistic accuracy report.
     */
    public boolean movedBeyondNoise(double minimumMeters, double accuracyMultiple) {
        if (!isKnown()) {
            return false;
        }
        double floor = Math.max(minimumMeters, medianHorizontalAccuracyMeters * accuracyMultiple);
        return netDisplacementMeters >= floor;
    }
}
