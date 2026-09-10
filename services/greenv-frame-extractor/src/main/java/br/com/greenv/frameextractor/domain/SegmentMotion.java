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

    /** Whether the camera moved far enough for the movement to be motion rather than noise. */
    public boolean movedBeyondNoise(double minimumMeters, double accuracyMultiple) {
        return isKnown() && movementMeters() >= noiseFloorMeters(minimumMeters, accuracyMultiple);
    }

    /**
     * The distance the floor is judged against, which depends on how the path was measured.
     *
     * <p>Doppler speed does not know where the phone is, so integrating it cannot accumulate
     * position error: the path is the honest reading and the straight line adds nothing. When there
     * is no speed and the path is a sum of great-circle hops the opposite holds, because that sum
     * accumulates fix noise while the displacement cancels it.
     */
    public double movementMeters() {
        return fromSpeed ? pathMeters : netDisplacementMeters;
    }

    /**
     * The movement below which nothing can be told from noise.
     *
     * <p>Exposed as well as applied, because whether a segment can reach this floor at all decides
     * whether the fixes answer the question.
     *
     * <p>Scaling the floor with fix accuracy only makes sense for the displacement. Applied to a
     * Doppler path it demanded car speed of a measurement that never used position: at 12 m
     * accuracy the bar stood at 25 m in ten seconds, which is 9 km/h, so a walk of 8.8 m at
     * 3 km/h was recorded, believed and then refused (segment 01a08885 #2, 9 September 2026).
     */
    public double noiseFloorMeters(double minimumMeters, double accuracyMultiple) {
        return fromSpeed
                ? minimumMeters
                : Math.max(minimumMeters, medianHorizontalAccuracyMeters * accuracyMultiple);
    }
}
