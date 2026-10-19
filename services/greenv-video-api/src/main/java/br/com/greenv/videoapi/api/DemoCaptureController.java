package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.port.DemoCaptureUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The FIAP Next demo: the phone ↔ gateway contract, version 1, under {@code /v1/demo/}.
 *
 * <p>Outside the API's own authentication on purpose. The two writes carry the demo's pairing
 * code instead, checked by the use case; the reads are public, because the site that shows them
 * is public and read-only. Nothing here is mapped unless {@code greenv.demo.enabled} is true.
 *
 * <p>The bodies are read from the request directly rather than bound, so that the pairing code is
 * checked before a single byte of a video is: a content type or a length is only judged once the
 * caller has shown it may send one.
 */
@RestController
@RequestMapping("/v1/demo")
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoCaptureController {

    private final DemoCaptureUseCase demo;

    public DemoCaptureController(DemoCaptureUseCase demo) {
        this.demo = demo;
    }

    @GetMapping("/health")
    ResponseEntity<DemoHealthResponse> health(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return noStore(DemoHealthResponse.of(demo.authorised(authorization)));
    }

    @PostMapping("/captures")
    ResponseEntity<DemoAnnounceResponse> announce(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) throws IOException {
        var capture = demo.announce(authorization, request.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(DemoAnnounceResponse.of(capture.id()));
    }

    @PutMapping("/captures/{captureId}/video")
    ResponseEntity<DemoUploadResponse> uploadVideo(
            @PathVariable String captureId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) throws IOException {
        var receipt = demo.receiveVideo(
                authorization,
                captureId,
                request.getContentType(),
                request.getContentLengthLong(),
                request.getInputStream());
        return noStore(DemoUploadResponse.from(receipt));
    }

    @GetMapping("/captures/{captureId}")
    ResponseEntity<DemoCaptureStatusResponse> capture(@PathVariable String captureId) {
        return noStore(DemoCaptureStatusResponse.from(demo.capture(captureId)));
    }

    /** Newest first; at most 100. */
    @GetMapping("/captures")
    ResponseEntity<DemoCaptureListResponse> captures(@RequestParam(defaultValue = "20") int limit) {
        return noStore(new DemoCaptureListResponse(
                demo.latest(limit).stream().map(DemoPhoneCaptureResponse::from).toList()));
    }

    @GetMapping("/captures/{captureId}/run")
    ResponseEntity<DemoRunResponse> run(@PathVariable String captureId) {
        return noStore(DemoRunResponse.from(demo.run(captureId)));
    }

    /**
     * Every answer here describes something that is still moving, and the site asks again every
     * two seconds: a cached answer is a wrong one.
     */
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
