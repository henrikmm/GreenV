package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

public record FrameExtractionRequest(
        int schemaVersion,
        int attempt,
        UUID jobId,
        String idempotencyKey,
        String sourceUri,
        String statusUri,
        String outputPrefixUri,
        String sourceGeneration,
        double requestedFps,
        int maxFrames,
        int longEdge,
        Instant requestedAt) {
}
