package br.com.greenv.frameextractor.domain;

import java.time.Instant;
import java.util.UUID;

public record SegmentExtractionRequest(
        int schemaVersion,
        int attempt,
        UUID sessionId,
        int segmentIndex,
        String idempotencyKey,
        String videoUri,
        String videoSha256,
        String telemetryUri,
        String telemetrySha256,
        String outputPrefixUri,
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
                videoUri,
                videoSha256,
                telemetryUri,
                telemetrySha256,
                outputPrefixUri,
                capturedAt,
                durationMillis,
                requestedAt);
    }
}
