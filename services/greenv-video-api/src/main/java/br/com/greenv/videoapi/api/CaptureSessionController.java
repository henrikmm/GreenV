package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.domain.CaptureSessionSort;
import br.com.greenv.videoapi.domain.SegmentQuery;
import br.com.greenv.videoapi.domain.Sentido;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/v2/capture-sessions")
public class CaptureSessionController {

    private final CaptureSessionUseCase captureSessionUseCase;

    public CaptureSessionController(CaptureSessionUseCase captureSessionUseCase) {
        this.captureSessionUseCase = captureSessionUseCase;
    }

    @PostMapping
    ResponseEntity<CaptureSessionResponse> create(@Valid @RequestBody CreateCaptureSessionRequest request) {
        var session = captureSessionUseCase.create(
                request.sessionId(),
                request.deviceId(),
                request.startedAt(),
                request.rodovia(),
                request.sentido());
        var response = CaptureSessionResponse.from(captureSessionUseCase.getSession(session.sessionId()));
        return ResponseEntity
                .created(URI.create(baseUrl() + "/v2/capture-sessions/" + session.sessionId()))
                .body(response);
    }

    @GetMapping("/{sessionId}")
    CaptureSessionResponse get(@PathVariable UUID sessionId) {
        return CaptureSessionResponse.from(captureSessionUseCase.getSession(sessionId));
    }

    @GetMapping("/{sessionId}/segments/{segmentIndex}")
    CaptureSegmentResponse getSegment(@PathVariable UUID sessionId, @PathVariable int segmentIndex) {
        return CaptureSegmentResponse.from(captureSessionUseCase.getSegment(sessionId, segmentIndex), baseUrl());
    }

    // A phone records MP4; a browser's MediaRecorder records WebM. Both are accepted because the
    // worker probes the container rather than trusting the name it stores the object under.
    @PutMapping(
            path = "/{sessionId}/segments/{segmentIndex}/video",
            consumes = {"video/mp4", "video/webm"})
    CaptureSegmentResponse uploadVideo(
            @PathVariable UUID sessionId,
            @PathVariable int segmentIndex,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Content-SHA256") String sha256,
            @RequestHeader("X-Captured-At") Instant capturedAt,
            @RequestHeader("X-Duration-Millis") long durationMillis,
            HttpServletRequest request) throws IOException {
        return CaptureSegmentResponse.from(
                captureSessionUseCase.uploadVideo(
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
                captureSessionUseCase.uploadTelemetry(
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
                .body(CaptureSegmentResponse.from(
                        captureSessionUseCase.completeSegment(sessionId, segmentIndex), baseUrl()));
    }

    @PostMapping("/{sessionId}/complete")
    ResponseEntity<CaptureSessionResponse> completeSession(
            @PathVariable UUID sessionId,
            @RequestBody Map<String, Object> body) {
        Object rawIndex = body.get("lastSegmentIndex");
        if (!(rawIndex instanceof Number number)) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "invalid_segment_index",
                    "lastSegmentIndex is required");
        }
        Instant endedAt = body.get("endedAt") == null ? null : Instant.parse(body.get("endedAt").toString());
        captureSessionUseCase.completeSession(sessionId, number.intValue(), endedAt);
        return ResponseEntity.accepted().body(CaptureSessionResponse.from(captureSessionUseCase.getSession(sessionId)));
    }

    /**
     * The sessions, newest first unless the caller asks for another order.
     *
     * <p>Until this existed nothing could open on a list: every read path needed an id the caller
     * already had, so a dashboard had no way to discover what had been captured.
     *
     * <p>The day and the order are the route's job, not the client's, for the reason {@link
     * MeasurementController} gives: a browser that filters the page it was given is answering
     * about the request rather than about the data, and cannot tell the two apart on screen.
     */
    @GetMapping
    PageResponse<CaptureSessionResponse> listSessions(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String rodovia,
            @RequestParam(required = false) String sentido,
            @RequestParam(defaultValue = "false") boolean measuredOnly,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant capturedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant capturedTo,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var query = new CaptureSessionQuery(
                state,
                rodovia,
                sentido == null ? null : Sentido.of(sentido),
                measuredOnly,
                capturedFrom,
                capturedTo,
                CaptureSessionSort.of(sort),
                limit,
                offset);
        return PageResponse.from(
                captureSessionUseCase.listSessions(query),
                CaptureSessionResponse::from);
    }

    /**
     * One page of a session's segments, in capture order.
     *
     * <p>This answered with the whole session once, on the reasoning that a session is finite. It
     * is, and an hour in the field is three hundred and sixty ten-second segments. Finite is not
     * bounded, and a client that reads them all then asks for each one's frames makes a request
     * per segment before it draws anything.
     */
    @GetMapping("/{sessionId}/segments")
    PageResponse<CaptureSegmentResponse> listSegments(
            @PathVariable UUID sessionId,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String url = baseUrl();
        return PageResponse.from(
                captureSessionUseCase.listSegments(new SegmentQuery(sessionId, level, limit, offset)),
                segment -> CaptureSegmentResponse.from(segment, url));
    }

    /** How many of this session's segments fall in each level, for the filter above the list. */
    @GetMapping("/{sessionId}/segments/summary")
    MeasurementSummaryResponse summariseSegments(@PathVariable UUID sessionId) {
        return MeasurementSummaryResponse.from(captureSessionUseCase.summariseSegments(sessionId));
    }

    /**
     * The session drawn: the camera path and the strip the measurement covered, as GeoJSON.
     *
     * <p>Served as bytes rather than as a record because every map library reads GeoJSON as it
     * stands, and a Java shape in the middle would only be translated back.
     */
    @GetMapping(path = "/{sessionId}/track", produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] track(@PathVariable UUID sessionId) {
        return captureSessionUseCase.track(sessionId);
    }

    @GetMapping("/{sessionId}/segments/{segmentIndex}/frames")
    List<SampledFrameResponse> frames(@PathVariable UUID sessionId, @PathVariable int segmentIndex) {
        return captureSessionUseCase.frames(sessionId, segmentIndex).stream()
                .map(frame -> SampledFrameResponse.from(frame, sessionId, segmentIndex, baseUrl()))
                .toList();
    }

    /** One published JPEG. The name is checked against the manifest before a key is built. */
    @GetMapping(path = "/{sessionId}/segments/{segmentIndex}/frames/{fileName}", produces = MediaType.IMAGE_JPEG_VALUE)
    byte[] frame(
            @PathVariable UUID sessionId, @PathVariable int segmentIndex, @PathVariable String fileName) {
        return captureSessionUseCase.frame(sessionId, segmentIndex, fileName);
    }

    /**
     * What this photograph contributed to the measurement.
     *
     * <p>Separate from the frame's bytes because it answers a different question and costs a
     * different amount: the JPEG is 65 KB straight out of storage, this walks every cell of the
     * assessment looking for the frame's votes.
     */
    @GetMapping(path = "/{sessionId}/segments/{segmentIndex}/frames/{fileName}/readings")
    FrameReadingsResponse frameReadings(
            @PathVariable UUID sessionId, @PathVariable int segmentIndex, @PathVariable String fileName) {
        return FrameReadingsResponse.from(
                captureSessionUseCase.frameReadings(sessionId, segmentIndex, fileName));
    }

    @GetMapping(
            path = "/{sessionId}/segments/{segmentIndex}/manifest",
            produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] manifest(@PathVariable UUID sessionId, @PathVariable int segmentIndex) {
        return captureSessionUseCase.manifest(sessionId, segmentIndex);
    }

    /**
     * The measured stretches inside one uploaded segment.
     *
     * <p>A segment is 10 seconds of video and, driven, a couple of hundred metres of road. It is
     * now cut into windows of about 25 m and each is reconstructed and measured on its own, so the
     * trecho a crew is sent to is a window and this is the list of them. Empty for a segment
     * measured whole.
     */
    @GetMapping("/{sessionId}/segments/{segmentIndex}/windows")
    List<CaptureSegmentResponse> windows(@PathVariable UUID sessionId, @PathVariable int segmentIndex) {
        String url = baseUrl();
        return captureSessionUseCase.windows(sessionId, segmentIndex).stream()
                .map(window -> CaptureSegmentResponse.from(window, url))
                .toList();
    }

    @GetMapping(
            path = "/{sessionId}/segments/{segmentIndex}/measurement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] measurement(
            @PathVariable UUID sessionId,
            @PathVariable int segmentIndex,
            @RequestParam(required = false) Integer window) {
        return captureSessionUseCase.measurement(sessionId, segmentIndex, window);
    }

    /**
     * The reconstruction this segment was measured from, as a download.
     *
     * <p>Two files, tens of megabytes each, so the body is a stream rather than an array and the
     * response carries a length a browser can show a progress bar against. The name offered to
     * the browser carries the run id, because a person downloading several of these needs to
     * tell them apart on disk.
     */
    @GetMapping(
            path = "/{sessionId}/segments/{segmentIndex}/depth/{fileName}",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Resource> depthArtifact(
            @PathVariable UUID sessionId,
            @PathVariable int segmentIndex,
            @PathVariable String fileName,
            @RequestParam(required = false) Integer window) {
        CaptureObjectStorage.ObjectContent content =
                captureSessionUseCase.depthArtifact(sessionId, segmentIndex, window, fileName);
        // The window is in the name because a segment now yields several reconstructions and a
        // person downloading them needs to tell them apart in a folder.
        String downloadName = window == null
                ? "%s-segmento-%d-%s".formatted(shortSession(sessionId), segmentIndex, fileName)
                : "%s-segmento-%d-trecho-%02d-%s"
                        .formatted(shortSession(sessionId), segmentIndex, window, fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.bytes())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(downloadName).build().toString())
                .body(new InputStreamResource(content.stream()));
    }

    /** Enough of a session id to tell two downloads apart in a folder, and no more. */
    private static String shortSession(UUID sessionId) {
        return sessionId.toString().substring(0, 8);
    }

    private static String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
