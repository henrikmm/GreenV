package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

public record CaptureSegmentDocument(
        UUID sessionId,
        int segmentIndex,
        String state,
        String idempotencyKey,
        Instant capturedAt,
        long durationMillis,
        String videoUri,
        String videoSha256,
        Long videoBytes,
        String telemetryUri,
        String telemetrySha256,
        Long telemetryBytes,
        String manifestUri,
        Integer frameCount,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasBothUploads() {
        return videoUri != null && telemetryUri != null;
    }
}
