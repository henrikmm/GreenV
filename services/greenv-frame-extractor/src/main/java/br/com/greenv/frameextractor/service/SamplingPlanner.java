package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.ScalePlan;
import org.springframework.stereotype.Component;

@Component
public class SamplingPlanner {

    public SamplingPlan sampling(double requestedFps, double durationSeconds, int maxFrames) {
        if (!(requestedFps > 0) || !(durationSeconds > 0) || maxFrames < 2) {
            throw new IllegalArgumentException("sampling inputs must be positive");
        }
        int requestedCount = Math.max(1, (int) Math.floor(requestedFps * durationSeconds));
        if (requestedCount <= maxFrames) {
            return new SamplingPlan(requestedCount, requestedFps, false, requestedCount);
        }
        return new SamplingPlan(maxFrames, maxFrames / durationSeconds, true, requestedCount);
    }

    public ScalePlan scale(int width, int height, int longEdge) {
        int longest = Math.max(width, height);
        if (longEdge <= 0 || longest <= longEdge) {
            return new ScalePlan(width, height, false);
        }
        double factor = (double) longEdge / longest;
        return new ScalePlan(even(width, factor), even(height, factor), true);
    }

    private static int even(int value, double factor) {
        return Math.max(2, (int) Math.round((value * factor) / 2.0) * 2);
    }
}
