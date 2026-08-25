package br.com.greenv.videoapi.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public record CreateCaptureSessionRequest(
        UUID sessionId,
        @NotBlank String deviceId,
        Instant startedAt) {
}
