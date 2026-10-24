package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.task.MockDemoGpuControlAdapter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The demo's recorded examples and its GPU switch, as the site uses them.
 *
 * <p>Storage is local, so every link a run hands out is a route on this API and can be followed.
 * The GPU is {@code MockDemoGpuControlAdapter}: nothing here reaches RunPod.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.demo.allowed-origins=https://greenv-demo.example",
            "greenv.demo.enabled=true",
            "greenv.demo.pairing-code=" + DemoExamplesAndGpuIntegrationTest.PAIRING_CODE,
            "greenv.demo.depth-adapter=mock",
            "greenv.demo.poll-initial-delay-ms=3600000"
        })
class DemoExamplesAndGpuIntegrationTest {

    static final String PAIRING_CODE = "demo-test-code-7319";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final Path TEST_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "greenv-demo-examples-" + TEST_ID);
    private static final String EXAMPLE = "03-celular-gabinete-pro6000";
    private static final String PREFIX = "demo/examples/" + EXAMPLE + "/";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.pipeline.root", () -> TEST_ROOT.resolve("pipeline").toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:demo-examples-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CaptureObjectStorage storage;

    @Autowired
    MockDemoGpuControlAdapter gpu;

    private final HttpClient client = HttpClient.newHttpClient();

    /** One test, in order: the list is empty until the index exists, and then the index is kept. */
    @Test
    void listsTheRecordedExamplesAndSignsTheirRunsInsideTheirOwnDirectory() throws Exception {
        HttpResponse<String> none = get("/examples");
        assertThat(none.statusCode()).isEqualTo(200);
        assertThat(json(none).path("examples").isArray()).isTrue();
        assertThat(json(none).path("examples")).isEmpty();

        byte[] glb = "glb-bytes".getBytes(StandardCharsets.UTF_8);
        put(PREFIX + "scene.glb", glb);
        put(PREFIX + "verge-result.npz", "npz-bytes".getBytes(StandardCharsets.UTF_8));
        for (int index = 1; index <= 3; index++) {
            put(PREFIX + "frames/frame-%04d.jpg".formatted(index), ("jpeg-" + index).getBytes(StandardCharsets.UTF_8));
        }
        put(PREFIX + "manifest.json", """
                {"schema":"verge.infer-manifest/0.1.0","run_id":"recorded","artifacts":[
                  {"name":"scene.glb","kind":"glb","size_bytes":9,"sha256":"%s","object_key":"%sscene.glb"},
                  {"name":"verge-result.npz","kind":"npz","size_bytes":9,"sha256":"x","object_key":"%sverge-result.npz","url":"https://stale.example/x"},
                  {"name":"video.mp4","kind":"video","object_key":"demo/cap-20261024-143000-a1b2/video.mp4","url":"https://stale.example/y"},
                  {"name":"escape.glb","kind":"glb","object_key":"%s../04-other/scene.glb"}
                ]}
                """.formatted(sha256(glb), PREFIX, PREFIX, PREFIX).getBytes(StandardCharsets.UTF_8));
        put("demo/examples/index.json", """
                {"examples":[{"id":"%s","title":"Gabinete de PC sobre a mesa","detail":"Celular · RTX PRO 6000",
                  "frames":3,"width":280,"height":504,"gpu":"NVIDIA RTX PRO 6000 Blackwell Server Edition",
                  "gpuSeconds":3.95,"truth":null}]}
                """.formatted(EXAMPLE).getBytes(StandardCharsets.UTF_8));

        JsonNode listed = json(get("/examples")).path("examples");
        assertThat(listed).hasSize(1);
        JsonNode entry = listed.get(0);
        assertThat(entry.path("id").asString()).isEqualTo(EXAMPLE);
        assertThat(entry.path("title").asString()).isEqualTo("Gabinete de PC sobre a mesa");
        assertThat(entry.path("detail").asString()).isEqualTo("Celular · RTX PRO 6000");
        assertThat(entry.path("gpuSeconds").asDouble()).isEqualTo(3.95);
        assertThat(entry.has("truth")).as("passed through as given, nulls included").isTrue();
        assertThat(entry.path("truth").isNull()).isTrue();

        HttpResponse<String> answered = get("/examples/" + EXAMPLE + "/run");
        assertThat(answered.statusCode()).isEqualTo(200);
        assertThat(answered.headers().firstValue("cache-control").orElse("")).contains("no-store");
        JsonNode run = json(answered);
        assertThat(run.path("gpu").asString()).isEqualTo("NVIDIA RTX PRO 6000 Blackwell Server Edition");
        assertThat(run.path("gpuSeconds").asDouble()).isEqualTo(3.95);
        assertThat(run.path("frames")).hasSize(3);
        String base = "http://127.0.0.1:" + port + "/v1/demo/examples/" + EXAMPLE + "/files/";
        assertThat(run.path("frames").get(0).asString()).isEqualTo(base + "frames/frame-0001.jpg");
        assertThat(run.path("frames").get(2).asString()).isEqualTo(base + "frames/frame-0003.jpg");
        HttpResponse<byte[]> frame = fetch(run.path("frames").get(1).asString());
        assertThat(frame.statusCode()).isEqualTo(200);
        assertThat(frame.headers().firstValue("content-type")).contains("image/jpeg");
        assertThat(frame.body()).isEqualTo("jpeg-2".getBytes(StandardCharsets.UTF_8));

        JsonNode artifacts = run.path("manifest").path("artifacts");
        assertThat(run.path("manifest").path("schema").asString()).isEqualTo("verge.infer-manifest/0.1.0");
        assertThat(artifacts).hasSize(4);
        assertThat(artifacts.get(0).path("url").asString()).isEqualTo(base + "scene.glb");
        HttpResponse<byte[]> scene = fetch(artifacts.get(0).path("url").asString());
        assertThat(scene.statusCode()).isEqualTo(200);
        assertThat(scene.body()).isEqualTo(glb);
        assertThat(artifacts.get(1).path("url").asString())
                .as("the stale link is replaced, not kept")
                .isEqualTo(base + "verge-result.npz");
        assertThat(artifacts.get(2).path("url").isNull()).as("a key outside the example is not signed").isTrue();
        assertThat(artifacts.get(3).path("url").isNull()).as("a key that climbs out is not signed").isTrue();

        HttpResponse<String> unknown = get("/examples/99-not-an-example/run");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).path("title").asString()).isEqualTo("demo_example_not_found");
        assertThat(get("/examples/Not_An_Id/run").statusCode()).isEqualTo(404);

        // There is no route that removes an example. An unmapped route falls through to the
        // error page, which is not public, so the refusal can arrive as a 401.
        HttpResponse<String> deleted = send(HttpRequest.newBuilder(uri("/examples/" + EXAMPLE))
                .header("authorization", "Bearer " + PAIRING_CODE)
                .DELETE());
        assertThat(deleted.statusCode()).isIn(401, 404, 405);
        assertThat(storage.exists(PREFIX + "scene.glb")).isTrue();
    }

    @Test
    void keepsTheGpuWarmOnlyWithThePairingCodeAndWritesNothingButWorkersMin() throws Exception {
        JsonNode cold = json(get("/gpu"));
        assertThat(cold.path("keepWarm").asBoolean()).isFalse();
        assertThat(cold.path("workersMin").asInt()).isZero();
        assertThat(cold.path("workersMax").asInt()).isEqualTo(1);
        assertThat(cold.path("idleTimeoutSeconds").asInt()).isEqualTo(5);
        for (String state : new String[] {"idle", "initializing", "ready", "running", "throttled", "unhealthy"}) {
            assertThat(cold.path("workers").path(state).isInt()).as(state).isTrue();
        }

        assertThat(putGpu("{\"keepWarm\":true}", null).statusCode()).isEqualTo(401);
        assertThat(putGpu("{\"keepWarm\":true}", "Bearer wrong-code-0000").statusCode()).isEqualTo(403);
        assertThat(putGpu("", "Bearer " + PAIRING_CODE).statusCode()).isEqualTo(400);
        assertThat(putGpu("{\"keepWarm\":\"yes\"}", "Bearer " + PAIRING_CODE).statusCode()).isEqualTo(400);
        assertThat(putGpu("{}", "Bearer " + PAIRING_CODE).statusCode()).isEqualTo(400);
        assertThat(gpu.written()).as("nothing refused reached the GPU").isEmpty();

        HttpResponse<String> warmed = putGpu("{\"keepWarm\":true}", "Bearer " + PAIRING_CODE);
        assertThat(warmed.statusCode()).isEqualTo(200);
        assertThat(json(warmed).path("keepWarm").asBoolean()).isTrue();
        assertThat(json(warmed).path("workersMin").asInt()).isEqualTo(1);
        assertThat(json(warmed).path("workersMax").asInt()).as("never written").isEqualTo(1);
        assertThat(json(get("/gpu")).path("keepWarm").asBoolean()).isTrue();

        HttpResponse<String> cooled = putGpu("{\"keepWarm\":false}", "Bearer " + PAIRING_CODE);
        assertThat(cooled.statusCode()).isEqualTo(200);
        assertThat(json(cooled).path("keepWarm").asBoolean()).isFalse();
        assertThat(json(cooled).path("workersMin").asInt()).isZero();
        assertThat(gpu.written()).containsExactly(1, 0);
    }

    /** The site's operator panel sends the PUT from its own origin, after a preflight. */
    @Test
    void letsTheSitePreflightTheGpuSwitch() throws Exception {
        HttpResponse<String> preflight = send(HttpRequest.newBuilder(uri("/gpu"))
                .header("Origin", "https://greenv-demo.example")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("access-control-allow-origin")).contains("https://greenv-demo.example");
        assertThat(preflight.headers().firstValue("access-control-allow-methods").orElse("")).contains("PUT");
        assertThat(preflight.headers().firstValue("access-control-allow-credentials")).isEmpty();
    }

    private void put(String key, byte[] bytes) throws Exception {
        storage.put(key, new ByteArrayInputStream(bytes), sha256(bytes), bytes.length);
    }

    private HttpResponse<String> putGpu(String body, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/gpu"))
                .header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("authorization", authorization);
        }
        return send(builder);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<byte[]> fetch(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/v1/demo" + path);
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
