package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import java.time.Instant;
import java.util.UUID;

public record CaptureSegmentResponse(
        int schemaVersion,
        UUID sessionId,
        int segmentIndex,
        String state,
        String idempotencyKey,
        Instant capturedAt,
        long durationMillis,
        Long videoBytes,
        String videoSha256,
        Long telemetryBytes,
        String telemetrySha256,
        String manifestUrl,
        Integer frameCount,
        String errorCode,
        String errorMessage) {

    public static CaptureSegmentResponse from(CaptureSegmentDocument segment, String baseUrl) {
        String manifestUrl = segment.manifestUri() == null
                ? null
                : baseUrl + "/v2/capture-sessions/" + segment.sessionId()
                        + "/segments/" + segment.segmentIndex() + "/manifest";
        return new CaptureSegmentResponse(
                1,
                segment.sessionId(),
                segment.segmentIndex(),
                segment.state(),
                segment.idempotencyKey(),
                segment.capturedAt(),
                segment.durationMillis(),
                segment.videoBytes(),
                segment.videoSha256(),
                segment.telemetryBytes(),
                segment.telemetrySha256(),
                manifestUrl,
                segment.frameCount(),
                segment.errorCode(),
                segment.errorMessage());
    }
}
