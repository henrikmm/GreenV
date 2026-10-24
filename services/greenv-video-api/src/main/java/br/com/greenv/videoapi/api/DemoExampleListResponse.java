package br.com.greenv.videoapi.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** The entries of {@code demo/examples/index.json}, passed through as whoever wrote it gave them. */
public record DemoExampleListResponse(List<JsonNode> examples) {
}
