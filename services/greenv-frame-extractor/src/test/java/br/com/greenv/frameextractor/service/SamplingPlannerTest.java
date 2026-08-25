package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class SamplingPlannerTest {

    private final SamplingPlanner planner = new SamplingPlanner();

    @Test
    void matchesTheExistingFullClipSamplingVectors() {
        assertPlan(10, 10, 16, 16, 1.6, true, 100);
        assertPlan(10, 10, 32, 32, 3.2, true, 100);
        assertPlan(2, 10, 32, 20, 2, false, 20);
        assertPlan(3.2, 10, 32, 32, 3.2, false, 32);
        assertPlan(10, 0.05, 16, 1, 10, false, 1);
        assertPlan(25, 8, 64, 64, 8, true, 200);
    }

    @Test
    void matchesTheExistingDownscaleVectors() {
        assertThat(planner.scale(3840, 2160, 1024))
                .extracting("width", "height", "scaled")
                .containsExactly(1024, 576, true);
        assertThat(planner.scale(1080, 1920, 1024))
                .extracting("width", "height", "scaled")
                .containsExactly(576, 1024, true);
        assertThat(planner.scale(4096, 2160, 1024))
                .extracting("width", "height", "scaled")
                .containsExactly(1024, 540, true);
        assertThat(planner.scale(800, 600, 1024))
                .extracting("width", "height", "scaled")
                .containsExactly(800, 600, false);
    }

    @Test
    void rejectsNonPositiveSamplingInputs() {
        assertThatThrownBy(() -> planner.sampling(0, 10, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.sampling(10, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertPlan(
            double fps,
            double duration,
            int maximum,
            int count,
            double effectiveFps,
            boolean capped,
            int requestedCount) {
        var result = planner.sampling(fps, duration, maximum);
        assertThat(result.count()).isEqualTo(count);
        assertThat(result.effectiveFps()).isCloseTo(effectiveFps, within(1e-9));
        assertThat(result.capped()).isEqualTo(capped);
        assertThat(result.requestedCount()).isEqualTo(requestedCount);
    }
}

