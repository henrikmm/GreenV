package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.config.S3StorageProperties;
import br.com.greenv.videoapi.domain.DepthJobRequest;
import br.com.greenv.videoapi.domain.DepthJobStatus;
import br.com.greenv.videoapi.port.DepthJobRunner;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The demo's depth jobs on a RunPod serverless endpoint.
 *
 * <p>The same conversation as {@code services/greenv-measurement-worker/src/infer-runpod.mjs}:
 * {@code POST /run} returns an id, {@code GET /status/<id>} answers until the job ends, and the
 * body is {@code greenv.depth-job/1} with frames by object key, because RunPod's request ceiling
 * is well under a hundred JPEGs. What differs is who waits. The worker blocks on the job; here
 * the pipeline asks once per tick and writes the answer down, so a replica that scales to zero
 * mid-job loses nothing.
 *
 * <p>Every call here can cost money: a job submitted wakes a GPU that bills for its whole
 * lifetime. That is why {@code executionTimeout} rides on the job itself as well as the
 * pipeline's own deadline — if this API vanished mid-job, RunPod would still stop the worker.
 */
@Component
@ConditionalOnExpression("${greenv.demo.enabled:false} and '${greenv.demo.depth-adapter:runpod}' == 'runpod'")
public class RunPodDepthJobAdapter implements DepthJobRunner {

    private static final Logger log = LoggerFactory.getLogger(RunPodDepthJobAdapter.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper objectMapper;
    private final String base;
    private final String apiKey;
    private final S3StorageProperties storage;
    private final Duration deadline;

    public RunPodDepthJobAdapter(
            ObjectMapper objectMapper, DemoProperties properties, S3StorageProperties storage) {
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
        String root = runpod.baseUrl().endsWith("/")
                ? runpod.baseUrl().substring(0, runpod.baseUrl().length() - 1)
                : runpod.baseUrl();
        this.base = root + "/" + segment(runpod.endpointId().strip());
        this.apiKey = runpod.apiKey().strip();
        this.storage = storage;
        this.deadline = properties.jobDeadline();
    }

    @Override
    public DepthJobStatus submit(DepthJobRequest request) {
        if (request.frames().size() < 2) {
            throw new IllegalArgumentException(
                    "depth inference needs at least 2 frames, got " + request.frames().size());
        }
        JsonNode started = call("POST", base + "/run", body(request));
        String jobId = started.path("id").asString("");
        if (jobId.isBlank()) {
            throw new IllegalStateException("RunPod accepted no job: " + abbreviate(started.toString()));
        }
        log.info("demo depth job {} submitted for {} with {} frames", jobId, request.captureId(), request.frames().size());
        return interpret(jobId, started);
    }

    @Override
    public DepthJobStatus status(String jobId) {
        return interpret(jobId, call("GET", base + "/status/" + segment(jobId), null));
    }

    @Override
    public void cancel(String jobId) {
        try {
            call("POST", base + "/cancel/" + segment(jobId), "{}");
            log.info("demo depth job {} cancelled", jobId);
        } catch (RuntimeException exception) {
            log.warn("could not cancel demo depth job {}: {}", jobId, exception.getMessage());
        }
    }

    /** The request body, in the shape the handler and the measurement worker already agree on. */
    String body(DepthJobRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fps", request.fps());
        params.put("process_res", request.processRes());
        params.put("max_frames", request.maxFrames());
        if (request.sourceDurationSeconds() != null && request.sourceDurationSeconds() > 0) {
            params.put("source_duration_s", request.sourceDurationSeconds());
        }
        Map<String, Object> storageBlock = new LinkedHashMap<>();
        storageBlock.put("bucket", blankToNull(storage.bucket()));
        storageBlock.put("endpoint", blankToNull(storage.endpoint()));
        storageBlock.put("region", blankToNull(storage.region()));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("frames", request.frames().stream()
                .map(frame -> Map.of("name", frame.name(), "key", frame.key()))
                .toList());
        input.put("storage", storageBlock);
        input.put("params", params);
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("input", input);
        job.put("policy", Map.of("executionTimeout", deadline.toMillis()));
        return objectMapper.writeValueAsString(job);
    }

    static DepthJobStatus interpret(String jobId, JsonNode answer) {
        String status = answer.path("status").asString("");
        return switch (status) {
            case "IN_QUEUE" -> DepthJobStatus.queued(jobId);
            case "IN_PROGRESS" -> DepthJobStatus.running(jobId);
            case "COMPLETED" -> {
                JsonNode output = answer.path("output");
                if (output.isMissingNode() || output.isNull()) {
                    yield DepthJobStatus.failed(jobId, "RunPod job " + jobId + " completed with no output");
                }
                // A handler that refused the job still completes, with the refusal as its output.
                if (output.has("error") && !output.has("artifacts")) {
                    yield DepthJobStatus.failed(jobId, abbreviate(output.path("error").asString(output.toString())));
                }
                yield DepthJobStatus.completed(jobId, output);
            }
            default -> DepthJobStatus.failed(
                    jobId,
                    "RunPod job " + jobId + " ended " + (status.isBlank() ? "without a status" : status)
                            + errorOf(answer));
        };
    }

    private JsonNode call(String method, String url, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30));
        if (body == null) {
            builder.GET();
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
            return objectMapper.readTree(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + url + " was interrupted", interrupted);
        } catch (java.io.IOException unreachable) {
            throw new IllegalStateException(method + " " + url + " failed: " + unreachable.getMessage(), unreachable);
        }
    }

    private static String errorOf(JsonNode answer) {
        JsonNode error = answer.path("error");
        if (error.isMissingNode() || error.isNull()) {
            return "";
        }
        return ": " + abbreviate(error.isString() ? error.asString() : error.toString());
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 400 ? value : value.substring(0, 400) + "…";
    }
}
