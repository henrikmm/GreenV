package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoGpuState;
import br.com.greenv.videoapi.port.DemoGpuControl;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The demo endpoint's settings on RunPod, through two of its APIs.
 *
 * <p>The management API ({@code rest.runpod.io/v1}) holds the endpoint's settings:
 * {@code GET /endpoints/{id}} reads {@code workersMin}, {@code workersMax} and
 * {@code idleTimeout}, and {@code PATCH /endpoints/{id}} with {@code {"workersMin": n}} changes
 * that one field and leaves every other as it was. The job API ({@code api.runpod.ai/v2}) is where
 * the workers are counted, {@code GET /{id}/health}.
 *
 * <p>The PATCH body is built here and only here, and holds {@code workersMin} alone: raising
 * {@code workersMax} or the idle timeout by accident would change what the demo can spend.
 */
@Component
@ConditionalOnExpression("${greenv.demo.enabled:false} and '${greenv.demo.depth-adapter:runpod}' == 'runpod'")
public class RunPodDemoGpuControlAdapter implements DemoGpuControl {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper objectMapper;
    private final String endpointUrl;
    private final String healthUrl;
    private final String apiKey;

    public RunPodDemoGpuControlAdapter(ObjectMapper objectMapper, DemoProperties properties) {
        DemoProperties.Runpod runpod = properties.runpod();
        if (runpod.endpointId() == null || runpod.endpointId().isBlank()) {
            throw new IllegalStateException(
                    "greenv.demo.runpod.endpoint-id is required when greenv.demo.depth-adapter=runpod");
        }
        if (runpod.apiKey() == null || runpod.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "greenv.demo.runpod.api-key is required when greenv.demo.depth-adapter=runpod");
        }
        this.objectMapper = objectMapper;
        String endpoint = URLEncoder.encode(runpod.endpointId().strip(), StandardCharsets.UTF_8);
        this.endpointUrl = withoutTrailingSlash(runpod.restBaseUrl()) + "/endpoints/" + endpoint;
        this.healthUrl = withoutTrailingSlash(runpod.baseUrl()) + "/" + endpoint + "/health";
        this.apiKey = runpod.apiKey().strip();
    }

    @Override
    public DemoGpuState state() {
        JsonNode endpoint = call("GET", endpointUrl, null);
        JsonNode workersMin = endpoint.path("workersMin");
        if (!workersMin.isNumber()) {
            throw new IllegalStateException("GET " + endpointUrl + " answered without workersMin");
        }
        JsonNode workers = call("GET", healthUrl, null).path("workers");
        return new DemoGpuState(
                workersMin.asInt(),
                endpoint.path("workersMax").asInt(0),
                endpoint.path("idleTimeout").asInt(0),
                new DemoGpuState.Workers(
                        workers.path("idle").asInt(0),
                        workers.path("initializing").asInt(0),
                        workers.path("ready").asInt(0),
                        workers.path("running").asInt(0),
                        workers.path("throttled").asInt(0),
                        workers.path("unhealthy").asInt(0)));
    }

    @Override
    public void setWorkersMin(int workersMin) {
        if (workersMin < 0 || workersMin > 1) {
            throw new IllegalArgumentException("the demo keeps at most one worker warm, not " + workersMin);
        }
        call("PATCH", endpointUrl, patchBody(workersMin));
    }

    /** {@code {"workersMin":n}} and nothing else. */
    String patchBody(int workersMin) {
        return objectMapper.writeValueAsString(Map.of("workersMin", workersMin));
    }

    private JsonNode call(String method, String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(TIMEOUT);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // The URL carries no secret; the key is only ever in the header.
                throw new IllegalStateException(
                        method + " " + url + " failed with " + response.statusCode() + ": "
                                + abbreviate(response.body()));
            }
            String text = response.body();
            return text == null || text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + url + " was interrupted", interrupted);
        } catch (IOException unreachable) {
            throw new IllegalStateException(method + " " + url + " failed: " + unreachable.getMessage(), unreachable);
        }
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 400 ? value : value.substring(0, 400) + "…";
    }
}
