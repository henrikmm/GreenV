package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.SamplingOptions;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
        @NotBlank String fileName,
        @NotBlank String contentType,
        @Min(1) long sizeBytes,
        @DecimalMin("0.1") @DecimalMax("60.0") Double requestedFps,
        @Min(2) @Max(512) Integer maxFrames,
        @Min(64) @Max(4096) Integer longEdge) {

    public SamplingOptions sampling() {
        return new SamplingOptions(
                requestedFps == null ? SamplingOptions.DEFAULT_FPS : requestedFps,
                maxFrames == null ? SamplingOptions.DEFAULT_MAX_FRAMES : maxFrames,
                longEdge == null ? SamplingOptions.DEFAULT_LONG_EDGE : longEdge);
    }
}
