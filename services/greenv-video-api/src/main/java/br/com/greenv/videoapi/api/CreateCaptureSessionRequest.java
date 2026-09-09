package br.com.greenv.videoapi.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record CreateCaptureSessionRequest(
        UUID sessionId,
        @NotBlank String deviceId,
        Instant startedAt,
        // Sent as text and turned into the four-value vocabulary by the service, so an unknown
        // sentido answers with this API's own problem code instead of a JSON parser's message.
        @Size(max = 32) String rodovia,
        String sentido) {
}
