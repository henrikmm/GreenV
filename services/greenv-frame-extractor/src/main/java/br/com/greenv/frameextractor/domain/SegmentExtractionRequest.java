package br.com.greenv.frameextractor.domain;

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

    public SegmentExtractionRequest nextAttempt() {
        return new SegmentExtractionRequest(
                schemaVersion,
                attempt + 1,
                sessionId,
                segmentIndex,
                idempotencyKey,
                videoObjectKey,
                videoSha256,
                telemetryObjectKey,
                telemetrySha256,
                outputPrefix,
                capturedAt,
                durationMillis,
                requestedAt);
    }
}
