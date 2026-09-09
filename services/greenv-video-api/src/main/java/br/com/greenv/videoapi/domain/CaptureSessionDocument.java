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
        Integer lastSegmentIndex,
        // A route is driven along one rodovia in one sentido for its whole length, so these belong
        // to the session rather than to each segment. Both are null for a capture recorded before
        // the app asked, and for a build that never asks; nothing downstream substitutes a value.
        String rodovia,
        Sentido sentido) {
}
