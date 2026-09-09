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
        return isKnown() && netDisplacementMeters >= noiseFloorMeters(minimumMeters, accuracyMultiple);
    }

    /**
     * The displacement below which movement cannot be told from fix noise.
     *
     * <p>Exposed as well as applied, because whether a segment can reach this floor at all decides
     * whether the fixes answer the question. At 50 km accuracy it stands at 100 km, which no
     * ten-second segment reaches at any speed, so "did not move" is the only output the arithmetic
     * permits and therefore not a finding.
     */
    public double noiseFloorMeters(double minimumMeters, double accuracyMultiple) {
        return Math.max(minimumMeters, medianHorizontalAccuracyMeters * accuracyMultiple);
    }
}
