package br.com.greenv.frameextractor.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record FrameExtractionRequest(
        @Min(1) @Max(1) int schemaVersion,
        @Min(0) int attempt,
        @NotNull UUID jobId,
        @NotBlank String idempotencyKey,
        @NotBlank String sourceUri,
        @NotBlank String statusUri,
        @NotBlank String outputPrefixUri,
        @NotBlank String sourceGeneration,
        @DecimalMin("0.1") @DecimalMax("60.0") double requestedFps,
        @Min(2) @Max(512) int maxFrames,
        @Min(64) @Max(4096) int longEdge,
        @NotNull Instant requestedAt) {

    public FrameExtractionRequest nextAttempt() {
        return new FrameExtractionRequest(
                schemaVersion,
                attempt + 1,
                jobId,
                idempotencyKey,
                sourceUri,
                statusUri,
                outputPrefixUri,
                sourceGeneration,
                requestedFps,
                maxFrames,
                longEdge,
                requestedAt);
    }
}
