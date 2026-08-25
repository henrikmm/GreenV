package br.com.greenv.frameextractor.domain;

public record SamplingPlan(
        int count,
        double effectiveFps,
        boolean capped,
        int requestedCount) {
}
