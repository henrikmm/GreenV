package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.port.DemoExampleUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo's recorded examples: public reads, nothing else. There is no write and no delete here
 * on purpose — the examples are uploaded to the bucket by hand, and the API only shows them.
 */
@RestController
@RequestMapping("/v1/demo/examples")
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoExampleController {

    private final DemoExampleUseCase examples;

    public DemoExampleController(DemoExampleUseCase examples) {
        this.examples = examples;
    }

    @GetMapping
    ResponseEntity<DemoExampleListResponse> examples() {
        return noStore(new DemoExampleListResponse(examples.examples()));
    }

    @GetMapping("/{exampleId}/run")
    ResponseEntity<DemoRunResponse> run(@PathVariable String exampleId) {
        return noStore(DemoRunResponse.from(examples.run(exampleId)));
    }

    /** Every link in a run is signed for this answer; a cached one may have expired. */
    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
