package br.com.greenv.frameextractor.domain;

import java.util.List;

/**
 * One stretch of road and the frames that reconstruct it — the unit a measurement runs on.
 *
 * <p>A ten-second segment is an upload bound, not an analysis unit: at 100 km/h it covers 278 m,
 * which is many stretches rather than one scene. Groups cut that distance into pieces short enough
 * that the frames inside one still look at the same place, so a segment publishes several
 * independent reconstructions instead of one stretched across a quarter of a kilometre.
 *
 * <p>Groups do not overlap. Each is a measurement of its own stretch, so there is nothing to stitch
 * across a boundary.
 *
 * @param index position within the segment, from zero
 * @param frameIndices encoded-frame indices chosen for this group, ascending
 * @param startMeters distance from the segment's first fix where the group begins
 * @param medianBaselineMeters typical camera translation between consecutive chosen frames
 * @param withinGradedEnvelope whether this group's frame count and length sit inside the range
 *     Verge Studio has actually been graded at; see {@code MEASUREMENTS.md}
 * @param published whether the JPEGs for this group were written, or only the plan recorded
 */
public record FrameGroup(
        int index,
        List<Integer> frameIndices,
        double startMeters,
        double endMeters,
        double medianBaselineMeters,
        double minBaselineMeters,
        double maxBaselineMeters,
        boolean withinGradedEnvelope,
        boolean published) {

    public FrameGroup {
        frameIndices = frameIndices == null ? List.of() : List.copyOf(frameIndices);
    }

    public int frameCount() {
        return frameIndices.size();
    }

    public double lengthMeters() {
        return endMeters - startMeters;
    }

    public FrameGroup asPublished() {
        return new FrameGroup(
                index,
                frameIndices,
                startMeters,
                endMeters,
                medianBaselineMeters,
                minBaselineMeters,
                maxBaselineMeters,
                withinGradedEnvelope,
                true);
    }
}
