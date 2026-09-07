package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

public record SegmentExtractionRequest(
        int schemaVersion,
        int attempt,
        UUID sessionId,
        int segmentIndex,
        String idempotencyKey,
        String videoObjectKey,
        String videoSha256,
        String telemetryObjectKey,
        String telemetrySha256,
        String outputPrefix,
        Instant capturedAt,
        long durationMillis,
        Instant requestedAt) {
}
