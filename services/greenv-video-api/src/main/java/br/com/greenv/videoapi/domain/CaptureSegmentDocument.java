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
        String measurementState,
        String measurementObjectKey,
        String measurementRunId,
        Boolean measurementIsMock,
        Instant measuredAt,
        /**
         * What a map can draw, derived from the packet rather than reported by the worker. Never
         * null: an unmeasured segment carries {@link MeasurementProjection#EMPTY} so a caller
         * reads a field rather than checking for a null object first.
         */
        MeasurementProjection measurement,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasBothUploads() {
        return videoObjectKey != null && telemetryObjectKey != null;
    }

    public boolean isMeasured() {
        return "measured".equals(measurementState);
    }
}
