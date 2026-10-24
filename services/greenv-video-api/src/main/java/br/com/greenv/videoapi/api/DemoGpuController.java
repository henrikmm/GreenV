package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.port.DemoGpuUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the demo's GPU is kept warm. The read is public; the switch takes the pairing code.
 *
 * <p>The body is read from the request directly rather than bound, as the capture writes do, so
 * the pairing code is checked before the body is judged at all.
 */
@RestController
@RequestMapping("/v1/demo/gpu")
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoGpuController {

    private final DemoGpuUseCase gpu;

    public DemoGpuController(DemoGpuUseCase gpu) {
        this.gpu = gpu;
    }

    @GetMapping
    ResponseEntity<DemoGpuResponse> gpu() {
        return noStore(DemoGpuResponse.from(gpu.gpu()));
    }

    @PutMapping
    ResponseEntity<DemoGpuResponse> keepWarm(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) throws IOException {
        return noStore(DemoGpuResponse.from(gpu.keepWarm(authorization, request.getInputStream())));
    }

    /** Workers come and go by the second; a cached answer is a wrong one. */
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
