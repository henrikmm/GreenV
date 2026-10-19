package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.DemoRun;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What the site draws a capture from.
 *
 * <p>{@code frames} are absolute, freshly signed links to the sampled JPEGs, in order, and empty
 * until they are in storage. {@code manifest} is the depth handler's manifest in its own wire form
 * (snake_case, {@code verge.infer-manifest/0.1.0}) with every artifact {@code url} re-signed, and
 * null until the capture is {@code fetched}.
 */
public record DemoRunResponse(List<String> frames, JsonNode manifest, String gpu, Double gpuSeconds) {

    public static DemoRunResponse from(DemoRun run) {
        return new DemoRunResponse(run.frames(), run.manifest(), run.gpu(), run.gpuSeconds());
    }
}
