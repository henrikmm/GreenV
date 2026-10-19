package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.DemoSampling;
import br.com.greenv.videoapi.domain.DemoVideoProbe;
import br.com.greenv.videoapi.port.DemoCaptureStore;
import br.com.greenv.videoapi.port.DemoFrameSampler;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The FIAP Next demo end to end, as the phone and the site drive it: announce, upload, then poll
 * until the pipeline reaches {@code fetched}, and read back what the site draws from.
 *
 * <p>Nothing here needs ffmpeg or a GPU. The sampler is a fake that writes three tiny JPEG-named
 * files, because the host that runs this suite has no ffmpeg; the depth job is
 * {@code MockDepthJobAdapter}, which walks the RunPod states one per poll and publishes placeholder
 * artifacts. Storage is local, so every URL the run hands out is a route on this API and can be
 * followed.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.security.allowed-origins=https://dashboard.example",
            "greenv.demo.allowed-origins=https://greenv-demo.example",
            "greenv.demo.enabled=true",
            "greenv.demo.pairing-code=" + DemoCaptureIntegrationTest.PAIRING_CODE,
            "greenv.demo.depth-adapter=mock",
            "greenv.demo.max-video-bytes=4096",
            "greenv.demo.poll-initial-delay-ms=100",
            "greenv.demo.poll-delay-ms=100"
        })
@Import(DemoCaptureIntegrationTest.FakeSampler.class)
class DemoCaptureIntegrationTest {

    static final String PAIRING_CODE = "demo-test-code-4821";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final Path TEST_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "greenv-demo-http-" + TEST_ID);
    private static final String META = """
            {"schema":"verge-phone-capture/1","capturedAt":"2026-10-24T14:30:00.000Z",
             "durationMs":8000,"settings":{"resolution":"1080p","fps":30},
             "sampling":{"fps":10,"maxFrames":80},"truth":null,"phoneOnly":"kept"}
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.pipeline.root", () -> TEST_ROOT.resolve("pipeline").toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:demo-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DemoCaptureStore store;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void walksACaptureFromAnnouncementToFetchedAndServesWhatTheSiteDraws() throws Exception {
        assertThat(json(get("/health", PAIRING_CODE)).path("authorised").asBoolean()).isTrue();
        JsonNode anonymousHealth = json(get("/health", null));
        assertThat(anonymousHealth.path("service").asString()).isEqualTo("greenv-demo-intake");
        assertThat(anonymousHealth.path("contract").asInt()).isEqualTo(1);
        assertThat(anonymousHealth.path("authorised").asBoolean()).isFalse();

        String id = announce();
        byte[] video = "not-really-an-mp4-but-the-sampler-is-fake".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploaded = upload(id, "video/mp4", video, PAIRING_CODE);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        JsonNode receipt = json(uploaded);
        assertThat(receipt.path("id").asString()).isEqualTo(id);
        assertThat(receipt.path("bytes").asLong()).isEqualTo(video.length);
        assertThat(receipt.path("sha256").asString()).isEqualTo(sha256(video));

        JsonNode status = awaitState(id, "fetched");
        List<String> phases = new ArrayList<>();
        status.path("phases").forEach(phase -> phases.add(phase.path("phase").asString()));
        assertThat(phases).containsExactly(
                "created", "uploading", "received", "extracting", "extracted", "uploading-frames",
                "waking", "inferring", "inferred", "fetched");
        JsonNode inferred = status.path("phases").get(8).path("detail");
        assertThat(inferred.path("gpu").asString()).isEqualTo("mock (no GPU)");
        assertThat(inferred.path("frames").asInt()).isEqualTo(3);
        assertThat(inferred.path("mock").asBoolean()).isTrue();
        assertThat(status.path("phases").get(0).has("detail")).as("no detail, no key").isFalse();
        assertThat(status.path("video").path("bytes").asLong()).isEqualTo(video.length);
        assertThat(status.path("result").path("mock").asBoolean()).isTrue();

        JsonNode run = json(get("/captures/" + id + "/run", null));
        assertThat(run.path("frames")).hasSize(3);
        String firstFrame = run.path("frames").get(0).asString();
        assertThat(firstFrame).isEqualTo(
                "http://127.0.0.1:" + port + "/v1/demo/captures/" + id + "/files/sampled-frames/frame-0001.jpg");
        HttpResponse<byte[]> frame = client.send(
                HttpRequest.newBuilder(URI.create(firstFrame)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(frame.statusCode()).isEqualTo(200);
        assertThat(frame.headers().firstValue("content-type")).contains("image/jpeg");
        assertThat(frame.body()).isEqualTo(FakeSampler.frameBytes(1));

        JsonNode manifest = run.path("manifest");
        assertThat(manifest.path("mock").asBoolean()).isTrue();
        assertThat(manifest.path("frames").path("count").asInt()).isEqualTo(3);
        assertThat(run.path("gpu").asString()).isEqualTo("mock (no GPU)");
        assertThat(manifest.path("artifacts")).hasSize(2);
        for (JsonNode artifact : manifest.path("artifacts")) {
            String key = artifact.path("object_key").asString();
            assertThat(key).startsWith("demo/" + id + "/depth/");
            String url = artifact.path("url").asString();
            assertThat(url).startsWith("http://127.0.0.1:" + port + "/v1/demo/captures/" + id + "/files/depth/");
            HttpResponse<byte[]> artifactBytes = client.send(
                    HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(artifactBytes.statusCode()).isEqualTo(200);
            assertThat(artifactBytes.body()).hasSize(artifact.path("size_bytes").asInt());
            assertThat(sha256(artifactBytes.body())).isEqualTo(artifact.path("sha256").asString());
        }

        JsonNode list = json(get("/captures?limit=5", null));
        JsonNode listed = list.path("captures").get(0);
        assertThat(listed.path("id").asString()).isEqualTo(id);
        assertThat(listed.path("state").asString()).isEqualTo("fetched");
        assertThat(listed.path("capturedAt").asString()).isEqualTo("2026-10-24T14:30:00.000Z");
        assertThat(listed.path("durationMs").asLong()).isEqualTo(8000);
        assertThat(listed.path("settings").path("resolution").asString()).isEqualTo("1080p");
        assertThat(listed.path("sampling").path("maxFrames").asInt()).isEqualTo(80);
        assertThat(listed.path("truth").isNull()).isTrue();
        assertThat(listed.path("video").path("probe").path("codec").asString()).isEqualTo("h264");
        assertThat(listed.path("events")).hasSize(10);

        assertThat(FakeSampler.lastAsked).containsExactly(10.0, 80.0);
        assertThat(upload(id, "video/mp4", video, PAIRING_CODE).statusCode())
                .as("a second upload of the same capture")
                .isEqualTo(409);
    }

    @Test
    void refusesWritesWithoutTheRightPairingCode() throws Exception {
        assertThat(post("/captures", META, null).statusCode()).isEqualTo(401);
        assertThat(post("/captures", META, "Basic " + PAIRING_CODE).statusCode()).isEqualTo(401);
        assertThat(post("/captures", META, "Bearer wrong-code-0000").statusCode()).isEqualTo(403);

        String id = announce();
        byte[] video = "video".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(id, "video/mp4", video, null).statusCode()).isEqualTo(401);
        assertThat(upload(id, "video/mp4", video, "wrong-code-0000").statusCode()).isEqualTo(403);
        // Refused before anything changed: the capture still waits for its video.
        assertThat(json(get("/captures/" + id, null)).path("state").asString()).isEqualTo("created");
    }

    @Test
    void refusesVideosItCannotTake() throws Exception {
        String id = announce();
        byte[] video = "video".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> wrongType = upload(id, "text/plain", video, PAIRING_CODE);
        assertThat(wrongType.statusCode()).isEqualTo(415);
        assertThat(json(wrongType).path("detail").asString()).contains("unsupported content-type");

        HttpResponse<String> noLength = send(HttpRequest.newBuilder(uri("/captures/" + id + "/video"))
                .header("authorization", "Bearer " + PAIRING_CODE)
                .header("content-type", "video/mp4")
                // A publisher with no known length makes the client send the body chunked.
                .PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(video))));
        assertThat(noLength.statusCode()).isEqualTo(411);

        HttpResponse<String> tooLarge = upload(id, "video/mp4", new byte[5000], PAIRING_CODE);
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(json(get("/captures/" + id, null)).path("state").asString()).isEqualTo("created");

        assertThat(upload("cap-20260101-000000-zzzz", "video/mp4", video, PAIRING_CODE).statusCode()).isEqualTo(404);
        assertThat(get("/captures/not-a-capture-id", null).statusCode()).isEqualTo(404);
        assertThat(post("/captures", "{\"schema\":\"something-else\"}", "Bearer " + PAIRING_CODE).statusCode())
                .isEqualTo(400);
        assertThat(post("/captures", "x".repeat(70 * 1024), "Bearer " + PAIRING_CODE).statusCode())
                .isEqualTo(413);
    }

    /** The public site lives on its own origin and reads without credentials. */
    @Test
    void answersTheSiteAcrossOrigins() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/captures"))
                .header("Origin", "https://greenv-demo.example")
                .GET());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("access-control-allow-origin"))
                .contains("https://greenv-demo.example");
        assertThat(response.headers().firstValue("access-control-allow-credentials"))
                .as("the demo routes grant no cookie to another origin")
                .isEmpty();
        assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");

        // The dashboard's credentialed CORS is not extended to the public site: an XSS there must
        // not be able to call the authenticated API with the visitor's cookie.
        HttpResponse<String> elsewhere = send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/sessions"))
                .header("Origin", "https://greenv-demo.example")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(elsewhere.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @Test
    void leaseIsHeldByOneReplicaAtATime() {
        String id = announceDirectly();
        Instant now = Instant.now();
        assertThat(store.claim(id, "replica-a", now, now.plusSeconds(60))).isTrue();
        assertThat(store.claim(id, "replica-b", now, now.plusSeconds(60))).isFalse();
        // After the lease runs out, the other replica may take over, and the first one's writes
        // are refused from then on.
        assertThat(store.claim(id, "replica-b", now.plusSeconds(61), now.plusSeconds(120))).isTrue();
        var capture = store.find(id).orElseThrow();
        assertThat(store.save(capture, null, "replica-a")).isFalse();
        assertThat(store.renew(id, "replica-a", now.plusSeconds(300))).as("a lost lease is not renewed").isFalse();
        assertThat(store.save(capture, null, "replica-b")).isTrue();
        assertThat(store.renew(id, "replica-b", now.plusSeconds(300))).isTrue();
        assertThat(store.claim(id, "replica-a", now.plusSeconds(150), now.plusSeconds(200)))
                .as("a renewed lease holds past its first expiry")
                .isFalse();
        store.release(id, "replica-b");
    }

    /**
     * The phone keeps a capture's id and retries a broken upload under it, as it does against the
     * Mac. The break is recorded on the timeline, and the retry is taken rather than refused.
     */
    @Test
    void takesTheRetryOfAnUploadThatBrokeOff() throws Exception {
        String id = announce();
        byte[] video = "video-bytes".getBytes(StandardCharsets.UTF_8);

        // Declares more than it sends, then closes its side: a phone that lost its connection.
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream output = socket.getOutputStream();
            output.write(("PUT /v1/demo/captures/" + id + "/video HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer " + PAIRING_CODE + "\r\n"
                    + "Content-Type: video/mp4\r\n"
                    + "Content-Length: " + (video.length + 100) + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(video);
            output.flush();
            socket.shutdownOutput();
            try {
                socket.getInputStream().readAllBytes();
            } catch (java.io.IOException reset) {
                // Whether the server answers or drops the connection is not what is under test.
            }
        }
        JsonNode broken = awaitPhase(id, "failed");
        assertThat(broken.path("phases").get(broken.path("phases").size() - 1).path("detail").path("failedIn").asString())
                .isEqualTo("uploading");

        HttpResponse<String> retried = upload(id, "video/mp4", video, PAIRING_CODE);
        assertThat(retried.statusCode()).as("the retry %s", retried.body()).isEqualTo(200);
        assertThat(json(retried).path("sha256").asString()).isEqualTo(sha256(video));
        assertThat(upload(id, "video/mp4", video, PAIRING_CODE).statusCode())
                .as("a capture that has its video takes no second one")
                .isEqualTo(409);
    }

    private String announceDirectly() {
        try {
            return announce();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String announce() throws Exception {
        HttpResponse<String> created = post("/captures", META, "Bearer " + PAIRING_CODE);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json(created);
        String id = body.path("id").asString();
        assertThat(id).matches("cap-\\d{8}-\\d{6}-[a-z0-9]{4}");
        assertThat(body.path("uploadPath").asString()).isEqualTo("/v1/demo/captures/" + id + "/video");
        return id;
    }

    private JsonNode awaitState(String id, String state) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode status = null;
        while (Instant.now().isBefore(deadline)) {
            status = json(get("/captures/" + id, null));
            if (state.equals(status.path("state").asString())) {
                return status;
            }
            assertThat(status.path("state").asString()).as("timeline %s", status).isNotEqualTo("failed");
            Thread.sleep(100);
        }
        throw new AssertionError("capture " + id + " never reached " + state + ": " + status);
    }

    /** Waits for the timeline's last line to be {@code phase}, without judging how it got there. */
    private JsonNode awaitPhase(String id, String phase) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode status = null;
        while (Instant.now().isBefore(deadline)) {
            status = json(get("/captures/" + id, null));
            if (phase.equals(status.path("state").asString())) {
                return status;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("capture " + id + " never reached " + phase + ": " + status);
    }

    private HttpResponse<String> get(String path, String pairingCode) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (pairingCode != null) {
            builder.header("authorization", "Bearer " + pairingCode);
        }
        return send(builder);
    }

    private HttpResponse<String> post(String path, String body, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("authorization", authorization);
        }
        return send(builder);
    }

    private HttpResponse<String> upload(String id, String contentType, byte[] body, String pairingCode)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/captures/" + id + "/video"))
                .header("content-type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (pairingCode != null) {
            builder.header("authorization", "Bearer " + pairingCode);
        }
        return send(builder);
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

    /** Three named frames and a plausible probe, in place of ffmpeg. */
    @TestConfiguration
    static class FakeSampler {

        static volatile List<Double> lastAsked = List.of();

        static byte[] frameBytes(int index) {
            return ("jpeg-" + index).getBytes(StandardCharsets.UTF_8);
        }

        @Bean
        @Primary
        DemoFrameSampler fakeDemoFrameSampler() {
            return (video, outputDirectory, fps, maxFrames, longEdge) -> {
                lastAsked = List.of(fps, (double) maxFrames);
                try {
                    Files.createDirectories(outputDirectory);
                    List<Path> frames = new ArrayList<>();
                    for (int index = 1; index <= 3; index++) {
                        Path frame = outputDirectory.resolve("frame-%04d.jpg".formatted(index));
                        Files.write(frame, frameBytes(index));
                        frames.add(frame);
                    }
                    DemoVideoProbe probe = new DemoVideoProbe(
                            "h264", 1920, 1080, 90, 30.0, 12_000_000L, false, "bt709", 8.0);
                    return new DemoSampling(frames, fps, false, 3, 576, 1024, probe);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            };
        }
    }
}
