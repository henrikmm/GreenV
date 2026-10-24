package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.DemoCaptureUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo's own objects, streamed by the API — only when storage is local.
 *
 * <p>With a bucket behind it the site is handed presigned links and fetches straight from the
 * bucket; this controller does not exist then. With local storage there is no bucket to sign for,
 * and this is what stands in for one, so the phone, the API and the site can run together on one
 * laptop. The three capture routes are the three places a capture keeps files:
 * {@code demo/<id>/<file>}, {@code demo/<id>/sampled-frames/<file>} and
 * {@code demo/<id>/depth/<run>/<file>}; the two example routes are where a recorded example keeps
 * them, {@code demo/examples/<id>/<file>} and {@code demo/examples/<id>/frames/<file>}.
 */
@RestController
@RequestMapping("/v1/demo")
@ConditionalOnExpression("${greenv.demo.enabled:false} and '${greenv.adapters.object-storage:local}' == 'local'")
public class DemoObjectController {

    private static final String MARKER = "/files/";

    private final DemoCaptureUseCase demo;

    public DemoObjectController(DemoCaptureUseCase demo) {
        this.demo = demo;
    }

    @GetMapping({
        "/captures/{captureId}/files/{name}",
        "/captures/{captureId}/files/sampled-frames/{name}",
        "/captures/{captureId}/files/depth/{runId}/{name}"
    })
    ResponseEntity<Resource> file(@PathVariable String captureId, HttpServletRequest request) {
        return serve("demo/" + captureId + "/", request);
    }

    @GetMapping({"/examples/{exampleId}/files/{name}", "/examples/{exampleId}/files/frames/{name}"})
    ResponseEntity<Resource> exampleFile(@PathVariable String exampleId, HttpServletRequest request) {
        return serve("demo/examples/" + exampleId + "/", request);
    }

    private ResponseEntity<Resource> serve(String prefix, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String rest = URLDecoder.decode(uri.substring(uri.indexOf(MARKER) + MARKER.length()), StandardCharsets.UTF_8);
        CaptureObjectStorage.ObjectContent content = demo.openObject(prefix + rest);
        return ResponseEntity.ok()
                .contentType(mediaType(rest))
                .contentLength(content.bytes())
                .cacheControl(CacheControl.noCache())
                .body(new InputStreamResource(content.stream()));
    }

    private static MediaType mediaType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".glb")) {
            return MediaType.parseMediaType("model/gltf-binary");
        }
        if (lower.endsWith(".mp4")) {
            return MediaType.parseMediaType("video/mp4");
        }
        if (lower.endsWith(".mov")) {
            return MediaType.parseMediaType("video/quicktime");
        }
        if (lower.endsWith(".webm")) {
            return MediaType.parseMediaType("video/webm");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
