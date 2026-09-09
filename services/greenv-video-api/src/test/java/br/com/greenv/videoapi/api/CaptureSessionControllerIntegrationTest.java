package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class CaptureSessionControllerIntegrationTest {

    private static final String TEST_API_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final Path TEST_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "greenv-capture-http-" + TEST_ID);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.pipeline.root", () -> TEST_ROOT.resolve("pipeline").toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:capture-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    IdentifierGenerator identifierGenerator;

    @MockitoBean
    SegmentWorkQueue taskPublisher;

    @Test
    void acceptsIdempotentVideoAndTelemetryUploadsAndRejectsChangedContent() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";
        HttpResponse<String> created = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"deviceId":"pilot-phone","startedAt":"2026-08-23T12:00:00Z"}
                        """)));
        assertThat(created.statusCode()).isEqualTo(201);
        String sessionId = objectMapper.readTree(created.body()).path("sessionId").asString();
        assertThat(UUID.fromString(sessionId).version()).isEqualTo(7);
        String segment = sessions + "/" + sessionId + "/segments/0";
        byte[] video = "encoded-video-segment".getBytes(StandardCharsets.UTF_8);
        byte[] telemetry = """
                {"schemaVersion":1,"sessionId":"%s","segmentIndex":0,
                 "capturedAtUtc":"2026-08-23T12:00:00Z","monotonicStartNanos":1000,
                 "frameClockSource":"segment_anchor","locations":[],"motions":[]}
                """.formatted(sessionId).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> firstVideo = upload(
                client, segment + "/video", "video/mp4", video, sha256(video));
        HttpResponse<String> repeatedVideo = upload(
                client, segment + "/video", "video/mp4", video, sha256(video));
        HttpResponse<String> changedVideo = upload(
                client,
                segment + "/video",
                "video/mp4",
                "different".getBytes(StandardCharsets.UTF_8),
                sha256("different".getBytes(StandardCharsets.UTF_8)));
        HttpResponse<String> telemetryResult = upload(
                client, segment + "/telemetry", "application/json", telemetry, sha256(telemetry));

        assertThat(firstVideo.statusCode()).isEqualTo(200);
        assertThat(repeatedVideo.statusCode()).isEqualTo(200);
        assertThat(changedVideo.statusCode()).isEqualTo(409);
        assertThat(telemetryResult.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(telemetryResult.body()).path("state").asString()).isEqualTo("uploading");
        assertThat(objectMapper.readTree(telemetryResult.body()).path("videoBytes").asLong()).isEqualTo(video.length);
        assertThat(objectMapper.readTree(telemetryResult.body()).path("telemetryBytes").asLong())
                .isEqualTo(telemetry.length);
    }

    @Test
    void rejectsAChangedIdempotencyIdentityForTheSameSegmentIndex() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";
        HttpResponse<String> created = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"deviceId\":\"pilot-phone-2\"}")));
        String sessionId = objectMapper.readTree(created.body()).path("sessionId").asString();
        byte[] video = "segment".getBytes(StandardCharsets.UTF_8);
        String target = sessions + "/" + sessionId + "/segments/0/video";

        assertThat(upload(client, target, "video/mp4", video, sha256(video)).statusCode()).isEqualTo(200);
        HttpResponse<String> conflict = send(client, HttpRequest.newBuilder(URI.create(target))
                .header("content-type", "video/mp4")
                .header("X-Idempotency-Key", "different-device-key")
                .header("X-Content-SHA256", sha256(video))
                .header("X-Captured-At", "2026-08-23T12:00:00Z")
                .header("X-Duration-Millis", "10000")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(video)));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(conflict.body()).path("title").asString())
                .isEqualTo("segment_identity_conflict");
    }

    @Test
    void createsTheSameClientAssignedSessionIdempotently() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";
        String sessionId = identifierGenerator.next().toString();
        String body = """
                {"sessionId":"%s","deviceId":"offline-phone","startedAt":"2026-08-23T12:00:00Z"}
                """.formatted(sessionId);

        HttpResponse<String> first = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        HttpResponse<String> repeated = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(repeated.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(first.body()).path("sessionId").asString()).isEqualTo(sessionId);
        assertThat(objectMapper.readTree(repeated.body()).path("sessionId").asString()).isEqualTo(sessionId);
    }

    @Test
    void acceptsALegacyUuidForAnAlreadyQueuedOfflineCapture() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";
        String legacySessionId = UUID.randomUUID().toString();
        String body = """
                {"sessionId":"%s","deviceId":"upgrading-phone"}
                """.formatted(legacySessionId);

        HttpResponse<String> response = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(response.body()).path("sessionId").asString())
                .isEqualTo(legacySessionId);
    }

    @Test
    void republishesAQueuedSegmentWhenCompletionIsRetried() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";
        HttpResponse<String> created = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"deviceId\":\"retry-phone\"}")));
        String sessionId = objectMapper.readTree(created.body()).path("sessionId").asString();
        String segment = sessions + "/" + sessionId + "/segments/0";
        byte[] video = "retry-video".getBytes(StandardCharsets.UTF_8);
        byte[] telemetry = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(upload(client, segment + "/video", "video/mp4", video, sha256(video)).statusCode())
                .isEqualTo(200);
        assertThat(upload(client, segment + "/telemetry", "application/json", telemetry, sha256(telemetry)).statusCode())
                .isEqualTo(200);
        assertThat(send(client, HttpRequest.newBuilder(URI.create(segment + "/complete"))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(202);
        assertThat(send(client, HttpRequest.newBuilder(URI.create(segment + "/complete"))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(202);

        verify(taskPublisher, times(2)).publish(org.mockito.ArgumentMatchers.argThat(request ->
                request.schemaVersion() == 2
                        && request.videoObjectKey().endsWith("/source.mp4")
                        && request.telemetryObjectKey().endsWith("/telemetry.json")
                        && !request.outputPrefix().contains("://")
                        // This capture named no road, and nothing invented one for it.
                        && request.rodovia() == null
                        && request.sentido() == null));
    }

    @Test
    void carriesTheRoadFromTheSessionOntoEverySegmentItQueues() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";
        HttpResponse<String> created = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"deviceId":"road-phone","rodovia":"br-101","sentido":"Norte"}
                        """)));
        assertThat(created.statusCode()).isEqualTo(201);
        // Normalised on the way in. `br-101` and `BR-101` joining as two roads is the failure
        // that actually happens on a dashboard keyed by (rodovia, sentido, km).
        assertThat(objectMapper.readTree(created.body()).path("rodovia").asString()).isEqualTo("BR-101");
        assertThat(objectMapper.readTree(created.body()).path("sentido").asString()).isEqualTo("norte");

        String sessionId = objectMapper.readTree(created.body()).path("sessionId").asString();
        String segment = sessions + "/" + sessionId + "/segments/0";
        byte[] video = "road-video".getBytes(StandardCharsets.UTF_8);
        byte[] telemetry = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(upload(client, segment + "/video", "video/mp4", video, sha256(video)).statusCode())
                .isEqualTo(200);
        assertThat(upload(client, segment + "/telemetry", "application/json", telemetry, sha256(telemetry))
                .statusCode()).isEqualTo(200);
        assertThat(send(client, HttpRequest.newBuilder(URI.create(segment + "/complete"))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(202);

        // The extraction request is the only way the road reaches the two workers: neither of them
        // has a database, and the measurement packet cannot be joined to a trecho without it.
        verify(taskPublisher).publish(org.mockito.ArgumentMatchers.argThat(request ->
                "BR-101".equals(request.rodovia()) && "norte".equals(request.sentido())));

        HttpResponse<String> reread = send(
                client, HttpRequest.newBuilder(URI.create(sessions + "/" + sessionId)).GET());
        assertThat(objectMapper.readTree(reread.body()).path("rodovia").asString()).isEqualTo("BR-101");
        assertThat(objectMapper.readTree(reread.body()).path("sentido").asString()).isEqualTo("norte");
    }

    @Test
    void refusesASentidoOutsideTheClosedVocabulary() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";

        HttpResponse<String> refused = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"deviceId":"road-phone","rodovia":"BR-101","sentido":"nordeste"}
                        """)));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(refused.body()).path("title").asString()).isEqualTo("invalid_sentido");
    }

    @Test
    void treatsAnEmptyRoadAsAbsentRatherThanAsAValue() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String sessions = "http://127.0.0.1:" + port + "/v2/capture-sessions";

        HttpResponse<String> created = send(client, HttpRequest.newBuilder(URI.create(sessions))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"deviceId":"blank-phone","rodovia":"   ","sentido":""}
                        """)));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(created.body()).path("rodovia").isNull()).isTrue();
        assertThat(objectMapper.readTree(created.body()).path("sentido").isNull()).isTrue();
    }

    private static HttpResponse<String> upload(
            HttpClient client,
            String uri,
            String contentType,
            byte[] body,
            String sha256) throws Exception {
        return send(client, HttpRequest.newBuilder(URI.create(uri))
                .header("content-type", contentType)
                .header("X-Idempotency-Key", "pilot-phone:route:0")
                .header("X-Content-SHA256", sha256)
                .header("X-Captured-At", "2026-08-23T12:00:00Z")
                .header("X-Duration-Millis", "10000")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        return client.send(
                request.header("Authorization", "Bearer " + TEST_API_TOKEN).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
