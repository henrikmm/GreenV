package br.com.greenv.frameextractor.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FrameManifest(
        int schemaVersion,
        UUID jobId,
        String sourceGeneration,
        double requestedFps,
        SamplingPlan sampling,
        ScalePlan scale,
        MediaProbeResult probe,
        Instant createdAt,
        List<FrameRecord> frames) {
}
