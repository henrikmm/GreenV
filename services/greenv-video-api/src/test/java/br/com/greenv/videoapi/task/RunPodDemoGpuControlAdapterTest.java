package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoGpuState;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The warm switch's RunPod conversation against a local stand-in for both of RunPod's APIs:
 * which routes are asked, what the PATCH carries, and how the answers are read. Nothing here
 * reaches RunPod.
 */
class RunPodDemoGpuControlAdapterTest {

    private static final String API_KEY = "rp-test-key";
    private static final String ENDPOINT = "/rest/v1/endpoints/greenv-demo-depth";
    private static final String HEALTH = "/v2/greenv-demo-depth/health";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private HttpServer server;
    private RunPodDemoGpuControlAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(route + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.put(route, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = answers.getOrDefault(route, "{\"error\":\"not here\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answers.containsKey(route) ? 200 : 404, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        DemoProperties properties = new DemoProperties(
                true, "pairing-code", 99_614_720L, 112, 10, 1024, 504, "runpod",
                Duration.ofMinutes(20), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(1),
                3, "http://127.0.0.1:8080", "ffmpeg", "ffprobe",
                new DemoProperties.Runpod(root + "/v2", "greenv-demo-depth", API_KEY, root + "/rest/v1/"));
        adapter = new RunPodDemoGpuControlAdapter(JsonMapper.builder().build(), properties);
        answers.put("GET " + ENDPOINT,
                "{\"id\":\"greenv-demo-depth\",\"name\":\"greenv-demo-depth\",\"workersMin\":1,"
                        + "\"workersMax\":2,\"idleTimeout\":5,\"gpuTypeIds\":[\"NVIDIA RTX PRO 6000\"]}");
        answers.put("GET " + HEALTH,
                "{\"jobs\":{\"completed\":4,\"failed\":0,\"inProgress\":0,\"inQueue\":0,\"retried\":0},"
                        + "\"workers\":{\"idle\":1,\"initializing\":0,\"ready\":1,\"running\":0,\"throttled\":0,\"unhealthy\":0}}");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsTheSettingsFromTheManagementApiAndTheWorkersFromHealth() {
        DemoGpuState state = adapter.state();

        assertThat(state).isEqualTo(new DemoGpuState(1, 2, 5, new DemoGpuState.Workers(1, 0, 1, 0, 0, 0)));
        assertThat(state.keepWarm()).isTrue();
        assertThat(requests).containsExactly(
                "GET " + ENDPOINT + " Bearer " + API_KEY,
                "GET " + HEALTH + " Bearer " + API_KEY);
    }

    @Test
    void patchesWorkersMinAndNothingElse() {
        answers.put("PATCH " + ENDPOINT, "{\"id\":\"greenv-demo-depth\",\"workersMin\":1}");

        adapter.setWorkersMin(1);
        assertThat(bodies.get("PATCH " + ENDPOINT)).isEqualTo("{\"workersMin\":1}");

        adapter.setWorkersMin(0);
        assertThat(bodies.get("PATCH " + ENDPOINT)).isEqualTo("{\"workersMin\":0}");
        assertThat(requests).containsExactly(
                "PATCH " + ENDPOINT + " Bearer " + API_KEY,
                "PATCH " + ENDPOINT + " Bearer " + API_KEY);
    }

    @Test
    void refusesToKeepMoreThanOneWorkerWarm() {
        assertThatThrownBy(() -> adapter.setWorkersMin(2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void failsWhenRunPodRefusesOrAnswersWithoutTheSetting() {
        answers.remove("GET " + HEALTH);
        assertThatThrownBy(adapter::state)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404")
                .hasMessageNotContaining(API_KEY);

        answers.put("GET " + ENDPOINT, "{\"id\":\"greenv-demo-depth\"}");
        assertThatThrownBy(adapter::state)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workersMin");
    }

    @Test
    void failsWhenRunPodCannotBeReached() {
        server.stop(0);

        assertThatThrownBy(adapter::state).isInstanceOf(IllegalStateException.class);
    }
}
