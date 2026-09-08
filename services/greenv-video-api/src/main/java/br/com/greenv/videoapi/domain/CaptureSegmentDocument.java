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
        String videoObjectKey,
        String videoSha256,
        Long videoBytes,
        String telemetryObjectKey,
        String telemetrySha256,
        Long telemetryBytes,
        String manifestObjectKey,
        Integer frameCount,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasBothUploads() {
        return videoObjectKey != null && telemetryObjectKey != null;
    }
}
