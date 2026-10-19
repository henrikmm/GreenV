package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.config.S3StorageProperties;
import br.com.greenv.videoapi.domain.DepthJobRequest;
import br.com.greenv.videoapi.domain.DepthJobStatus;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The RunPod conversation against a local stand-in: what is sent, and how each answer is read.
 * Nothing here reaches RunPod.
 */
class RunPodDepthJobAdapterTest {

    private static final String API_KEY = "rp-test-key";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private HttpServer server;
    private RunPodDepthJobAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(route + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.put(route, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = answers.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answers.containsKey(route) ? 200 : 404, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        DemoProperties properties = new DemoProperties(
                true, "pairing-code", 99_614_720L, 112, 10, 1024, 504, "runpod",
                Duration.ofMinutes(20), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(1),
                3, "http://127.0.0.1:8080", "ffmpeg", "ffprobe",
                new DemoProperties.Runpod(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v2", "greenv-demo-depth", API_KEY));
        S3StorageProperties storage = new S3StorageProperties(
                "greenv-captures", "auto", "https://account.r2.cloudflarestorage.com", false, "a", "s");
        adapter = new RunPodDepthJobAdapter(objectMapper, properties, storage);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void submitsFramesByKeyInTheDepthJobShape() {
        answers.put("POST /v2/greenv-demo-depth/run", "{\"id\":\"job-1\",\"status\":\"IN_QUEUE\"}");

        DepthJobStatus started = adapter.submit(request());

        assertThat(started).isEqualTo(DepthJobStatus.queued("job-1"));
        assertThat(requests).containsExactly("POST /v2/greenv-demo-depth/run Bearer " + API_KEY);
        JsonNode body = objectMapper.readTree(bodies.get("POST /v2/greenv-demo-depth/run"));
        JsonNode input = body.path("input");
        assertThat(input.path("frames").get(0).path("name").asString()).isEqualTo("frame-0001.jpg");
        assertThat(input.path("frames").get(1).path("key").asString())
                .isEqualTo("demo/cap-20261024-143000-a1b2/sampled-frames/frame-0002.jpg");
        assertThat(input.path("storage").path("bucket").asString()).isEqualTo("greenv-captures");
        assertThat(input.path("storage").path("endpoint").asString())
                .isEqualTo("https://account.r2.cloudflarestorage.com");
        assertThat(input.path("storage").path("region").asString()).isEqualTo("auto");
        assertThat(input.path("params").path("fps").asDouble()).isEqualTo(10.0);
        assertThat(input.path("params").path("process_res").asInt()).isEqualTo(504);
        assertThat(input.path("params").path("max_frames").asInt()).isEqualTo(112);
        assertThat(input.path("params").path("source_duration_s").asDouble()).isEqualTo(8.0);
        // RunPod stops the worker itself if this API is gone when the deadline passes.
        assertThat(body.path("policy").path("executionTimeout").asLong()).isEqualTo(20 * 60 * 1000);
    }

    @Test
    void readsEachStatusRunPodReports() {
        answers.put("GET /v2/greenv-demo-depth/status/job-1", "{\"id\":\"job-1\",\"status\":\"IN_PROGRESS\"}");
        assertThat(adapter.status("job-1").state()).isEqualTo(DepthJobStatus.State.RUNNING);

        answers.put("GET /v2/greenv-demo-depth/status/job-1",
                "{\"id\":\"job-1\",\"status\":\"COMPLETED\",\"output\":{\"run_id\":\"r1\",\"artifacts\":[]}}");
        DepthJobStatus completed = adapter.status("job-1");
        assertThat(completed.state()).isEqualTo(DepthJobStatus.State.COMPLETED);
        assertThat(completed.output().path("run_id").asString()).isEqualTo("r1");

        answers.put("GET /v2/greenv-demo-depth/status/job-1",
                "{\"id\":\"job-1\",\"status\":\"COMPLETED\",\"output\":{\"error\":\"frames come from 2 directories\"}}");
        DepthJobStatus refused = adapter.status("job-1");
        assertThat(refused.state()).isEqualTo(DepthJobStatus.State.FAILED);
        assertThat(refused.error()).contains("frames come from 2 directories");

        answers.put("GET /v2/greenv-demo-depth/status/job-1",
                "{\"id\":\"job-1\",\"status\":\"FAILED\",\"error\":\"CUDA out of memory\"}");
        DepthJobStatus failed = adapter.status("job-1");
        assertThat(failed.state()).isEqualTo(DepthJobStatus.State.FAILED);
        assertThat(failed.error()).contains("FAILED").contains("CUDA out of memory");
    }

    @Test
    void cancelsAndToleratesAJobThatIsAlreadyGone() {
        answers.put("POST /v2/greenv-demo-depth/cancel/job-1", "{\"id\":\"job-1\",\"status\":\"CANCELLED\"}");
        adapter.cancel("job-1");
        adapter.cancel("job-unknown");
        assertThat(requests).containsExactly(
                "POST /v2/greenv-demo-depth/cancel/job-1 Bearer " + API_KEY,
                "POST /v2/greenv-demo-depth/cancel/job-unknown Bearer " + API_KEY);
    }

    @Test
    void namesTheFailureWhenRunPodRefusesTheCall() {
        assertThatThrownBy(() -> adapter.status("job-missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed with 404");
    }

    private static DepthJobRequest request() {
        String prefix = "demo/cap-20261024-143000-a1b2/sampled-frames/";
        return new DepthJobRequest(
                "cap-20261024-143000-a1b2",
                List.of(
                        new DepthJobRequest.Frame("frame-0001.jpg", prefix + "frame-0001.jpg"),
                        new DepthJobRequest.Frame("frame-0002.jpg", prefix + "frame-0002.jpg")),
                10,
                504,
                112,
                8.0);
    }
}
