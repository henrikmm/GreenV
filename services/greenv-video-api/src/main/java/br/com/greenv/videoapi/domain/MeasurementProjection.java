package br.com.greenv.videoapi.domain;

/**
 * The part of a measurement a map can draw, derived from the packet worker 2 left in storage.
 *
 * <p>The packet itself stays where it was written. This is what a list query needs: a height to
 * colour by, a shape to draw, and enough of the quality summary that a reader can tell a confident
 * reading from a thin one. Deriving it here rather than storing what the worker reports means a
 * wrong projection is repaired by re-reading object storage, never by paying for the GPU again.
 *
 * @param extent95P95M the 95th percentile of the measured cells' own {@code extent95M}, in metres.
 *     Named for the 95th it was until 14 September 2026; the name stays because the installed
 *     capture app reads it
 * @param extent95MaxM the tallest measured cell
 * @param level 1, 2 or 3 on the thresholds below; null when nothing could be measured
 * @param cellsMeasured cells that produced a height
 * @param cellsAbstained cells the cameras saw but could not measure
 * @param coverage measured over observed, never over the verge — nothing knows the verge's area
 * @param trackGeoJson the camera path as a GeoJSON {@code LineString}, or null without fixes
 * @param trackLengthM how far that path runs on the ground, which is the only length this system
 *     knows. Null when there are fewer than two distinct fixes, and then no length was measured
 *     rather than the length being zero
 * @param trackLocationQuality the worst fix quality along the track
 */
public record MeasurementProjection(
        Double extent95P95M,
        Double extent95MaxM,
        Integer level,
        Integer cellsMeasured,
        Integer cellsAbstained,
        Double coverage,
        Double trackCenterLat,
        Double trackCenterLon,
        String trackGeoJson,
        Double trackLengthM,
        String trackLocationQuality) {

    public static final MeasurementProjection EMPTY =
            new MeasurementProjection(null, null, null, null, null, null, null, null, null, null, null);

    /** Below this a verge reads as mown. */
    public static final double LEVEL_2_FLOOR_M = 0.10;

    /** Above this it reads as overdue. */
    public static final double LEVEL_3_FLOOR_M = 0.30;

    /**
     * The mowing level for a height in metres, on the two thresholds the dashboard already draws.
     *
     * <p>Null in, null out, and that matters more than it looks: an unmeasured cell is not a short
     * one. {@code missingAreaMeaning} ships in every packet saying exactly that — "unknown:
     * unobserved, occluded, excluded or no grass; not short grass" — so a height nobody
     * established must never arrive at the lowest priority.
     *
     * <p>The thresholds are Motiva's operational bands and are still marked
     * {@code draft-not-motiva-approved} in the packet's own threshold exploration. They decide
     * what a screen is coloured, never whether a crew is sent.
     */
    public static Integer levelFor(Double extent95M) {
        if (extent95M == null || extent95M.isNaN()) {
            return null;
        }
        if (extent95M > LEVEL_3_FLOOR_M) {
            return 3;
        }
        return extent95M >= LEVEL_2_FLOOR_M ? 2 : 1;
    }

    public boolean isEmpty() {
        return equals(EMPTY);
    }
}
