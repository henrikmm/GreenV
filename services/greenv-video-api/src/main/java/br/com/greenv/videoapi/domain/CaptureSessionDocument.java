package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

public record CaptureSessionDocument(
        UUID sessionId,
        String deviceId,
        String state,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Integer lastSegmentIndex) {
}
