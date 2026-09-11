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
        String errorMessage,
        // Worker 2's side, in the same shape as the manifest above: the state, when it happened,
        // and a link rather than the packet. A reader that only got `state: "ready"` could not
        // tell a segment nobody measured from one measured an hour ago, and had to probe the
        // measurement route and read a 409 to find out.
        String measurementState,
        Instant measuredAt,
        String measurementRunId,
        // Never omitted when a measurement exists. A packet built on the fixture mock describes
        // another scene entirely, and a field that is absent reads as "no" to every client.
        Boolean measurementIsMock,
        String measurementUrl) {

    public static CaptureSegmentResponse from(CaptureSegmentDocument segment, String baseUrl) {
        String segmentPath = "/v2/capture-sessions/" + segment.sessionId()
                + "/segments/" + segment.segmentIndex();
        String manifestUrl = segment.manifestObjectKey() == null
                ? null
                : baseUrl + segmentPath + "/manifest";
        // The key is what proves the packet exists; the URL is only how a client fetches it.
        String measurementUrl = segment.measurementObjectKey() == null
                ? null
                : baseUrl + segmentPath + "/measurement";
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
                segment.errorMessage(),
                segment.measurementState(),
                segment.measuredAt(),
                segment.measurementRunId(),
                segment.measurementIsMock(),
                measurementUrl);
    }
}
