package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.FrameGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupPlannerTest {

    private static final int FPS = 30;
    private static final int SEGMENT_SECONDS = 10;
    private static final int CAP = 112;

    private final GroupPlanner planner = new GroupPlanner();

    /** 60 km/h covers 167 m, which is nine 20 m stretches. */
    @Test
    void cutsASteadyDriveIntoEvenStretches() {
        var plan = planAt(60);

        assertThat(plan).hasSize(8);
        assertThat(plan).allSatisfy(group ->
                assertThat(group.lengthMeters()).isCloseTo(166.7 / 8, within(0.1)));
        assertThat(plan.getFirst().frameCount()).isBetween(30, 45);
    }

    /**
     * The envelope table from the plan. Frames per group fall as speed rises because the camera
     * cannot record faster, and below 64 the group leaves the range Verge Studio has graded.
     */
    @Test
    void reportsWhenAGroupLeavesTheGradedEnvelope() {
        assertThat(planAt(20)).allMatch(FrameGroup::withinGradedEnvelope);
        assertThat(planAt(30)).allMatch(FrameGroup::withinGradedEnvelope);
        // ~34 km/h is where a group stops fitting 64 frames, the smallest count ever graded.
        assertThat(planAt(40)).noneMatch(FrameGroup::withinGradedEnvelope);
        assertThat(planAt(60)).noneMatch(FrameGroup::withinGradedEnvelope);
        assertThat(planAt(100)).noneMatch(FrameGroup::withinGradedEnvelope);
    }

    @Test
    void spacesFramesByDistanceSoTheBaselineHoldsAcrossAnAcceleration() {
        // Rest to 60 km/h over the segment: time-uniform sampling would crowd the start.
        List<EncodedFrameTimestamp> frames = frames();
        Map<Integer, Double> distance = accelerating(frames);

        var plan = planner.plan(
                frames, f -> distance.get(f.index()), distance.get(frames.getLast().index()),
                GroupPlanner.DEFAULT_GROUP_METERS, CAP);

        assertThat(plan).isNotEmpty();
        // Every group covers the same ground, however fast the vehicle was crossing it.
        double first = plan.getFirst().lengthMeters();
        assertThat(plan).allSatisfy(g -> assertThat(g.lengthMeters()).isCloseTo(first, within(0.01)));
        // And the frame budget follows the speed: the slow first stretch gets more views.
        assertThat(plan.getFirst().frameCount()).isGreaterThan(plan.getLast().frameCount());
    }

    /**
     * A faster camera is the one lever that actually moves the envelope. Nothing here is configured
     * for it: more encoded frames simply means more of them fall inside each 20 m stretch, so the
     * planner spends the budget it already had on a tighter baseline.
     */
    @Test
    void usesTheExtraFramesAFasterCameraProvides() {
        var at30 = planAt(100, 30);
        var at120 = planAt(100, 120);

        // Same road, same stretches - only the views inside them change.
        assertThat(at120).hasSameSizeAs(at30);
        assertThat(at120.getFirst().frameCount()).isGreaterThan(3 * at30.getFirst().frameCount());
        assertThat(at120.getFirst().medianBaselineMeters())
                .isLessThan(at30.getFirst().medianBaselineMeters() / 3);

        // And that is what carries 100 km/h back inside the range the instrument was graded at.
        assertThat(at30).noneMatch(FrameGroup::withinGradedEnvelope);
        assertThat(at120).allMatch(FrameGroup::withinGradedEnvelope);
    }

    /** Past a point the GPU ceiling binds instead of the camera, and the extra frames are dropped. */
    @Test
    void stillRespectsTheFrameCeilingWhenTheCameraOutrunsIt() {
        assertThat(planAt(60, 240))
                .allSatisfy(group -> assertThat(group.frameCount()).isLessThanOrEqualTo(CAP));
        assertThat(planAt(60, 240).getFirst().frameCount()).isEqualTo(CAP);
    }

    @Test
    void yieldsNothingWhenTheVehicleNeverMoved() {
        List<EncodedFrameTimestamp> frames = frames();
        assertThat(planner.plan(frames, f -> 0.0, 0.0, GroupPlanner.DEFAULT_GROUP_METERS, CAP))
                .isEmpty();
    }

    /** No group may run past the longest camera path in the graded evidence. */
    @Test
    void neverPlansAStretchLongerThanTheGradedMaximum() {
        for (int kmh : new int[] {5, 10, 20, 30, 60, 100, 120}) {
            assertThat(planAt(kmh)).allSatisfy(group -> assertThat(group.lengthMeters())
                    .isLessThanOrEqualTo(GroupPlanner.GRADED_MAXIMUM_METERS));
        }
    }

    @Test
    void neverExceedsTheGpuFrameCeiling() {
        // 10 km/h would want 216 frames in a 20 m stretch; the cap is what stops it.
        assertThat(planAt(10)).allSatisfy(g -> assertThat(g.frameCount()).isLessThanOrEqualTo(CAP));
    }

    @Test
    void neverRepeatsAFrameWithinAGroup() {
        for (int kmh : new int[] {5, 10, 20, 60, 100}) {
            assertThat(planAt(kmh)).allSatisfy(group ->
                    assertThat(group.frameIndices()).doesNotHaveDuplicates());
        }
    }

    @Test
    void refusesInputItCannotPlanFrom() {
        assertThat(planner.plan(null, f -> 0.0, 100, 20, CAP)).isEmpty();
        assertThat(planner.plan(frames(), f -> 0.0, -1, 20, CAP)).isEmpty();
        assertThat(planner.plan(frames(), f -> 0.0, 100, 0, CAP)).isEmpty();
    }

    private List<FrameGroup> planAt(int kilometresPerHour) {
        return planAt(kilometresPerHour, FPS);
    }

    private List<FrameGroup> planAt(int kilometresPerHour, int fps) {
        List<EncodedFrameTimestamp> frames = frames(fps);
        double metersPerSecond = kilometresPerHour / 3.6;
        Map<Integer, Double> distance = new HashMap<>();
        for (EncodedFrameTimestamp frame : frames) {
            distance.put(frame.index(), frame.presentationTimeNanos() / 1e9 * metersPerSecond);
        }
        double path = metersPerSecond * SEGMENT_SECONDS;
        return planner.plan(
                frames, f -> distance.get(f.index()), path, GroupPlanner.DEFAULT_GROUP_METERS, CAP);
    }

    /** Rest to 16.67 m/s with constant acceleration, integrated to distance. */
    private static Map<Integer, Double> accelerating(List<EncodedFrameTimestamp> frames) {
        double finalSpeed = 16.666_67;
        double acceleration = finalSpeed / SEGMENT_SECONDS;
        Map<Integer, Double> distance = new HashMap<>();
        for (EncodedFrameTimestamp frame : frames) {
            double t = frame.presentationTimeNanos() / 1e9;
            distance.put(frame.index(), 0.5 * acceleration * t * t);
        }
        return distance;
    }

    private static List<EncodedFrameTimestamp> frames() {
        return frames(FPS);
    }

    private static List<EncodedFrameTimestamp> frames(int fps) {
        int count = fps * SEGMENT_SECONDS;
        List<EncodedFrameTimestamp> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add(new EncodedFrameTimestamp(i, Math.round(i * 1_000_000_000.0 / fps), i % 30 == 0));
        }
        return frames;
    }
}
