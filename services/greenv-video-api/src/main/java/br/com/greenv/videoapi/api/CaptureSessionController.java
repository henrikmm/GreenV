package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.service.CaptureSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/v2/capture-sessions")
public class CaptureSessionController {

    private final CaptureSessionService service;

    public CaptureSessionController(CaptureSessionService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<CaptureSessionResponse> create(@Valid @RequestBody CreateCaptureSessionRequest request) {
        var session = service.create(request);
        var response = service.getSession(session.sessionId());
        return ResponseEntity
                .created(URI.create(baseUrl() + "/v2/capture-sessions/" + session.sessionId()))
                .body(response);
    }

    @GetMapping("/{sessionId}")
    CaptureSessionResponse get(@PathVariable UUID sessionId) {
        return service.getSession(sessionId);
    }

    @GetMapping("/{sessionId}/segments/{segmentIndex}")
    CaptureSegmentResponse getSegment(@PathVariable UUID sessionId, @PathVariable int segmentIndex) {
        return CaptureSegmentResponse.from(service.getSegment(sessionId, segmentIndex), baseUrl());
    }

    @PutMapping(path = "/{sessionId}/segments/{segmentIndex}/video", consumes = "video/mp4")
    CaptureSegmentResponse uploadVideo(
            @PathVariable UUID sessionId,
            @PathVariable int segmentIndex,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Content-SHA256") String sha256,
            @RequestHeader("X-Captured-At") Instant capturedAt,
            @RequestHeader("X-Duration-Millis") long durationMillis,
            HttpServletRequest request) throws IOException {
        return CaptureSegmentResponse.from(
                service.uploadVideo(
                        sessionId,
                        segmentIndex,
                        idempotencyKey,
                        capturedAt,
                        durationMillis,
                        sha256,
                        request.getInputStream()),
                baseUrl());
    }

    @PutMapping(
            path = "/{sessionId}/segments/{segmentIndex}/telemetry",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    CaptureSegmentResponse uploadTelemetry(
            @PathVariable UUID sessionId,
            @PathVariable int segmentIndex,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Content-SHA256") String sha256,
            @RequestHeader("X-Captured-At") Instant capturedAt,
            @RequestHeader("X-Duration-Millis") long durationMillis,
            HttpServletRequest request) throws IOException {
        return CaptureSegmentResponse.from(
                service.uploadTelemetry(
                        sessionId,
                        segmentIndex,
                        idempotencyKey,
                        capturedAt,
                        durationMillis,
                        sha256,
                        request.getInputStream()),
                baseUrl());
    }

    @PostMapping("/{sessionId}/segments/{segmentIndex}/complete")
    ResponseEntity<CaptureSegmentResponse> completeSegment(
            @PathVariable UUID sessionId,
            @PathVariable int segmentIndex) {
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(CaptureSegmentResponse.from(service.completeSegment(sessionId, segmentIndex), baseUrl()));
    }

    @PostMapping("/{sessionId}/complete")
    ResponseEntity<CaptureSessionResponse> completeSession(
            @PathVariable UUID sessionId,
            @RequestBody Map<String, Object> body) {
        Object rawIndex = body.get("lastSegmentIndex");
        if (!(rawIndex instanceof Number number)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_segment_index",
                    "lastSegmentIndex is required");
        }
        Instant endedAt = body.get("endedAt") == null ? null : Instant.parse(body.get("endedAt").toString());
        service.completeSession(sessionId, number.intValue(), endedAt);
        return ResponseEntity.accepted().body(service.getSession(sessionId));
    }

    @GetMapping(
            path = "/{sessionId}/segments/{segmentIndex}/manifest",
            produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] manifest(@PathVariable UUID sessionId, @PathVariable int segmentIndex) {
        return service.manifest(sessionId, segmentIndex);
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
