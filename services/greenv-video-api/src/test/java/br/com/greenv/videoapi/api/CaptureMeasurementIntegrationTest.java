package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The half of the pipeline that did not exist: worker 2 wrote its packet and nothing in the control
 * plane knew. These pin what the API does once it hears the announcement.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class CaptureMeasurementIntegrationTest {

    private static final String TEST_API_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final Path TEST_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "greenv-measurement-" + TEST_ID);

    private static final String PACKET = """
            {"schemaVersion":"greenv.measurement-result/1.0.0","runId":"20260908-000000-abcdef",\
            "mock":true,"measurement":{"quality":{"operationalStatus":"not-ready"}}}""";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.pipeline.root", () -> TEST_ROOT.resolve("pipeline").toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:measurement-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    CaptureSessionStore store;

    @Autowired
    CaptureSessionUseCase captureSessionUseCase;

    @Autowired
    CaptureObjectStorage objectStorage;

    @MockitoBean
    SegmentWorkQueue taskPublisher;

    @Test
    void servesThePacketOnlyAfterTheWorkerAnnouncesIt() throws Exception {
        UUID sessionId = givenASegment();

        assertThat(get(sessionId).statusCode())
                .as("a segment nobody measured has no packet to serve")
                .isEqualTo(409);

        objectStorage.put(
                CaptureObjectKeys.measurement(sessionId, 0),
                new ByteArrayInputStream(PACKET.getBytes(StandardCharsets.UTF_8)),
                sha256(PACKET),
                1024);
        captureSessionUseCase.recordMeasurement(new SegmentMeasurementAnnouncement(
                sessionId, 0, "20260908-000000-abcdef", true, Instant.parse("2026-09-08T03:00:00Z")));

        HttpResponse<String> measured = get(sessionId);
        assertThat(measured.statusCode()).isEqualTo(200);
        assertThat(measured.body())
                .as("the packet is served as stored, not re-serialised")
                .isEqualTo(PACKET);

        var segment = store.getSegment(sessionId, 0);
        assertThat(segment.measurementState()).isEqualTo("measured");
        assertThat(segment.measurementRunId()).isEqualTo("20260908-000000-abcdef");
        assertThat(segment.measurementIsMock()).isTrue();
        assertThat(segment.measuredAt()).isEqualTo(Instant.parse("2026-09-08T03:00:00Z"));
        assertThat(segment.measurementObjectKey())
                .as("the key is derived here, never taken from the message")
                .isEqualTo(CaptureObjectKeys.measurement(sessionId, 0));
    }

    @Test
    void ignoresAnAnnouncementForASegmentThisDeploymentNeverSaw() {
        // A replayed queue, or a database that was reset under a running worker. Inventing a row
        // would be worse than forgetting, and failing would block every message behind it.
        captureSessionUseCase.recordMeasurement(new SegmentMeasurementAnnouncement(
                UUID.randomUUID(), 0, "run", false, Instant.parse("2026-09-08T03:00:00Z")));
    }

    private UUID givenASegment() {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-08T02:00:00Z");
        // No rodovia and no sentido: this test is about the measurement, and an unnamed capture is
        // the one every session recorded before the app started asking.
        store.createSession(new br.com.greenv.videoapi.domain.CaptureSessionDocument(
                sessionId, "pilot-phone", "recording", now, null, now, now, now.plusSeconds(3600),
                null, null, null));
        store.ensureSegment(sessionId, 0, "%s:0".formatted(sessionId), now, 10_000, now);
        return sessionId;
    }

    private HttpResponse<String> get(UUID sessionId) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/v2/capture-sessions/" + sessionId + "/segments/0/measurement"))
                        .header("authorization", "Bearer " + TEST_API_TOKEN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
