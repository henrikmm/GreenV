package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.FrameGroup;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Cuts a segment's travelled distance into stretches and picks the frames that reconstruct each.
 *
 * <p>Frames are spaced by <b>distance</b>, not by time. Multi-view geometry recovers depth from the
 * camera's translation between views, so time is only a proxy for the thing that matters and the
 * proxy breaks the moment speed varies: sampling at a fixed rate crowds frames together where the
 * vehicle is slow — pulling away from a light puts half of them in the first twenty metres — and
 * spreads them where it is fast.
 *
 * <p>The stationary case falls out of this rather than needing a special rule. A parked vehicle
 * covers no distance, so it yields no group and publishes no frames.
 */
@Component
public class GroupPlanner {

    /**
     * Verge Studio's graded evidence covers camera paths of roughly 14–25 m
     * ({@code measurement/MEASUREMENTS.md}, and the door fixture's 13.91 m track), and twenty
     * metres kept a group inside it.
     *
     * <p>Ten is below that band on purpose. A ten-second segment on foot covers eight to ten
     * metres, so a twenty-metre target rounds to one group the walk never fills, and nothing about
     * the pipeline can be exercised without a car. Ten metres makes a walk testable at the cost of
     * a shorter baseline than anything graded, so a reading taken from one is evidence that the
     * pipeline ran, not evidence of a height.
     */
    public static final double DEFAULT_GROUP_METERS = 10.0;

    /** Smallest frame count Verge Studio has graded — 64 frames, at {@code MEASUREMENTS.md:56}. */
    public static final int GRADED_MINIMUM_FRAMES = 64;

    /** Longest camera path in the graded evidence. Beyond it a group is an extrapolation. */
    public static final double GRADED_MAXIMUM_METERS = 25.0;

    /**
     * Shortest camera path in the graded evidence, the door fixture's 13.91 m track rounded up.
     *
     * <p>Checked as well as the maximum since the target dropped to ten metres. Without it every
     * short group claimed the envelope simply by holding enough frames, and a walk would have been
     * reported as graded evidence when nothing at that baseline has ever been graded.
     */
    public static final double GRADED_MINIMUM_METERS = 14.0;

    /**
     * Plans the groups for one segment.
     *
     * @param frames encoded frames, ascending by presentation time
     * @param distanceOf cumulative distance in metres for a frame, from {@link MotionProfile}
     * @param pathMeters total distance the segment covered
     * @param groupMeters target stretch length per group
     * @param maxFramesPerGroup the GPU's frame ceiling
     */
    public List<FrameGroup> plan(
            List<EncodedFrameTimestamp> frames,
            java.util.function.ToDoubleFunction<EncodedFrameTimestamp> distanceOf,
            double pathMeters,
            double groupMeters,
            int maxFramesPerGroup) {
        if (frames == null || frames.size() < 2 || !(pathMeters > 0) || !(groupMeters > 0)) {
            return List.of();
        }

        double[] distances = new double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            distances[i] = distanceOf.applyAsDouble(frames.get(i));
        }

        // Aim for the target length, but never let a group run past the longest camera path
        // Verge Studio has graded. Rounding alone would leave a 29 m segment as one 29 m group;
        // ceiling alone would shorten every group and cost frames the camera did record.
        int groupCount = Math.max(
                Math.max(1, (int) Math.round(pathMeters / groupMeters)),
                (int) Math.ceil(pathMeters / GRADED_MAXIMUM_METERS - 1e-9));
        double actualGroupLength = pathMeters / groupCount;
        double origin = distances[0];

        List<FrameGroup> groups = new ArrayList<>(groupCount);
        for (int g = 0; g < groupCount; g++) {
            double start = origin + g * actualGroupLength;
            double end = origin + (g + 1) * actualGroupLength;
            List<Integer> chosen = selectWithin(distances, start, end, maxFramesPerGroup);
            if (chosen.size() < 2) {
                // Fewer than two distinct viewpoints is not a reconstruction. Dropped rather than
                // published as something the measurement stage would refuse anyway.
                continue;
            }
            groups.add(describe(groups.size(), chosen, distances, start, end));
        }
        return List.copyOf(groups);
    }

    /**
     * Picks frames at even distance intervals inside one stretch, capped at the GPU ceiling.
     *
     * <p>De-duplicates: when the vehicle is slow, several targets resolve to the same encoded frame,
     * and repeating it would hand the model zero-baseline pairs.
     */
    private static List<Integer> selectWithin(double[] distances, double start, double end, int cap) {
        int first = lowerBound(distances, start);
        int last = upperBound(distances, end);
        if (last <= first) {
            return List.of();
        }
        int available = last - first + 1;
        int wanted = Math.min(cap, available);
        if (wanted < 2) {
            return List.of();
        }

        LinkedHashSet<Integer> chosen = new LinkedHashSet<>(wanted);
        double span = distances[last] - distances[first];
        for (int k = 0; k < wanted; k++) {
            double target = span <= 0
                    ? distances[first]
                    : distances[first] + span * k / (double) (wanted - 1);
            chosen.add(nearest(distances, first, last, target));
        }
        return List.copyOf(chosen);
    }

    private static FrameGroup describe(
            int index, List<Integer> chosen, double[] distances, double start, double end) {
        double[] baselines = new double[chosen.size() - 1];
        for (int i = 1; i < chosen.size(); i++) {
            baselines[i - 1] = distances[chosen.get(i)] - distances[chosen.get(i - 1)];
        }
        java.util.Arrays.sort(baselines);
        double median = baselines.length % 2 == 1
                ? baselines[baselines.length / 2]
                : (baselines[baselines.length / 2 - 1] + baselines[baselines.length / 2]) / 2.0;

        double length = end - start;
        boolean graded = chosen.size() >= GRADED_MINIMUM_FRAMES
                && length >= GRADED_MINIMUM_METERS
                && length <= GRADED_MAXIMUM_METERS;

        return new FrameGroup(
                index,
                chosen,
                start,
                end,
                median,
                baselines[0],
                baselines[baselines.length - 1],
                graded,
                false);
    }

    private static int nearest(double[] distances, int from, int to, double target) {
        int best = from;
        double bestGap = Math.abs(distances[from] - target);
        for (int i = from + 1; i <= to; i++) {
            double gap = Math.abs(distances[i] - target);
            if (gap < bestGap) {
                best = i;
                bestGap = gap;
            }
        }
        return best;
    }

    private static int lowerBound(double[] distances, double value) {
        for (int i = 0; i < distances.length; i++) {
            if (distances[i] >= value) {
                return i;
            }
        }
        return distances.length - 1;
    }

    private static int upperBound(double[] distances, double value) {
        for (int i = distances.length - 1; i >= 0; i--) {
            if (distances[i] <= value) {
                return i;
            }
        }
        return 0;
    }
}
