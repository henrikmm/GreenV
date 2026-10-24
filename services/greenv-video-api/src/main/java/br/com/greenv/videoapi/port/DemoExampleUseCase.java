package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.DemoRun;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The demo's recorded examples ("exemplos"): reconstructions made beforehand and uploaded under
 * {@code demo/examples/}, so the site has something real to show before the first visitor films
 * anything, and when the GPU is cold.
 *
 * <p>Read-only and public. They are not captures — no record, no timeline, no retention — and
 * nothing in the API writes or deletes them.
 */
public interface DemoExampleUseCase {

    /** The entries of {@code demo/examples/index.json} as given; empty when there is no index. */
    List<JsonNode> examples();

    /** The same shape a capture's run has, signed now. Throws not-found for an id not in the index. */
    DemoRun run(String exampleId);
}
