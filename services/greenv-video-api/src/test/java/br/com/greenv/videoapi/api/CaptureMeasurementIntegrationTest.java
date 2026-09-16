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
        captureSessionUseCase.recordMeasurement(SegmentMeasurementAnnouncement.ofSegment(
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

    /**
     * How a client learns a measurement exists without asking for it.
     *
     * <p>The segment document used to end at {@code state: "ready"}, which is the same value
     * before and after worker 2 runs. A reader had to call the measurement route and read a 409
     * to find out, which is a probe rather than an answer, and no dashboard could list what had
     * been measured without one request per segment.
     */
    @Test
    void theSegmentItselfSaysWhetherItHasBeenMeasured() throws Exception {
        UUID sessionId = givenASegment();

        assertThat(getSegment(sessionId).body())
                .as("before a measurement every measurement field is null, and the link is absent")
                .contains("\"measurementState\":null")
                .contains("\"measurementUrl\":null")
                .contains("\"measurementIsMock\":null");

        objectStorage.put(
                CaptureObjectKeys.measurement(sessionId, 0),
                new ByteArrayInputStream(PACKET.getBytes(StandardCharsets.UTF_8)),
                sha256(PACKET),
                1024);
        captureSessionUseCase.recordMeasurement(SegmentMeasurementAnnouncement.ofSegment(
                sessionId, 0, "20260908-000000-abcdef", true, Instant.parse("2026-09-08T03:00:00Z")));

        String body = getSegment(sessionId).body();
        assertThat(body)
                .contains("\"measurementState\":\"measured\"")
                .contains("\"measurementRunId\":\"20260908-000000-abcdef\"")
                .contains("\"measuredAt\":\"2026-09-08T03:00:00Z\"")
                .as("a packet built on the fixture mock must say so here, not only inside itself")
                .contains("\"measurementIsMock\":true");
        assertThat(body)
                .as("the link is built the way manifestUrl is, so a client never composes a key")
                .contains("/v2/capture-sessions/" + sessionId + "/segments/0/measurement");
    }

    @Test
    void ignoresAnAnnouncementForASegmentThisDeploymentNeverSaw() {
        // A replayed queue, or a database that was reset under a running worker. Inventing a row
        // would be worse than forgetting, and failing would block every message behind it.
        captureSessionUseCase.recordMeasurement(SegmentMeasurementAnnouncement.ofSegment(
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

    private HttpResponse<String> getSegment(UUID sessionId) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/v2/capture-sessions/" + sessionId + "/segments/0"))
                        .header("authorization", "Bearer " + TEST_API_TOKEN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }


    /**
     * The routes that let a dashboard open on something.
     *
     * <p>Before these, every read needed an id the caller already had, so there was no way to
     * discover what had been captured at all.
     */
    @Test
    void listsSessionsWithTheirCountersAndTheirSegments() throws Exception {
        UUID sessionId = givenASegment();

        String sessions = get("/v2/capture-sessions?limit=5").body();
        assertThat(sessions)
                .contains(sessionId.toString())
                .contains("\"total\"")
                .as("a list opens on how much of a session is done")
                .contains("\"segmentCount\":1")
                .contains("\"measuredSegmentCount\":0");

        givenAMeasurement(sessionId);

        assertThat(get("/v2/capture-sessions?measuredOnly=true").body())
                .contains("\"measuredSegmentCount\":1");
        assertThat(get("/v2/capture-sessions/" + sessionId + "/segments").body())
                .contains("\"measurementState\":\"measured\"");
        assertThat(get("/v2/measurements").body()).contains(sessionId.toString());
    }

    @Test
    void anUnknownSessionIsNotAnEmptyList() throws Exception {
        assertThat(get("/v2/capture-sessions/" + UUID.randomUUID() + "/segments").statusCode())
                .as("a mistyped id must read as absent, not as a session that recorded nothing")
                .isEqualTo(404);
    }

    /**
     * The manifest is the publication record, so it is also the guest list. A caller that could
     * name any object could read any object, including another session's.
     */
    @Test
    void servesOnlyTheFramesTheManifestLists() throws Exception {
        UUID sessionId = givenASegment();
        String manifest = "{\"sampledFrames\":[{\"fileName\":\"frame-0001.jpg\",\"sizeBytes\":64}]}";
        objectStorage.put(
                CaptureObjectKeys.manifest(sessionId, 0),
                new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)),
                sha256(manifest),
                1024);
        store.recordVideo(sessionId, 0, CaptureObjectKeys.manifest(sessionId, 0), sha256(manifest), 1, NOW);
        jdbcManifest(sessionId);

        assertThat(get("/v2/capture-sessions/" + sessionId + "/segments/0/frames").body())
                .contains("frame-0001.jpg")
                .as("unmeasured, so nothing joined this image to a fix")
                .contains("\"latitude\":null");

        assertThat(get("/v2/capture-sessions/" + sessionId + "/segments/0/frames/frame-9999.jpg").statusCode())
                .isEqualTo(404);
    }

    /**
     * The reconstruction is the one artifact a person may want to open in their own viewer, and
     * it is also the one whose name must never come out of the URL: the key is built from the two
     * names the depth service writes and the run id on the segment's own row.
     */
    @Test
    void servesTheReconstructionUnderTheRunThatComputedIt() throws Exception {
        UUID sessionId = givenASegment();

        assertThat(get("/v2/capture-sessions/" + sessionId + "/segments/0/depth/scene.glb").statusCode())
                .as("nothing measured yet, so there is no run to serve geometry from")
                .isEqualTo(409);

        givenAMeasurement(sessionId);

        assertThat(get("/v2/capture-sessions/" + sessionId + "/segments/0/depth/scene.glb").statusCode())
                .as("measured, but this packet kept no reconstruction")
                .isEqualTo(409);
        assertThat(get("/v2/capture-sessions/" + sessionId + "/segments/0/depth/assessment.json").statusCode())
                .as("a name that is not one of the two is refused, never turned into a key")
                .isEqualTo(404);

        String mesh = "glTF-pretend-bytes";
        objectStorage.put(
                CaptureObjectKeys.depthArtifact(sessionId, 0, "20260908-000000-abcdef", "scene.glb"),
                new ByteArrayInputStream(mesh.getBytes(StandardCharsets.UTF_8)),
                sha256(mesh),
                1024);

        HttpResponse<String> download =
                get("/v2/capture-sessions/" + sessionId + "/segments/0/depth/scene.glb");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(mesh);
        assertThat(download.headers().firstValue("content-disposition").orElse(""))
                .as("a browser saves it under a name that says which segment it belongs to")
                .contains("attachment")
                .contains(sessionId.toString().substring(0, 8) + "-segmento-0-scene.glb");
        assertThat(download.headers().firstValue("content-length").orElse(""))
                .as("streamed, but the length is known, so a download shows progress")
                .isEqualTo(String.valueOf(mesh.length()));
    }

    private void givenAMeasurement(UUID sessionId) throws Exception {
        objectStorage.put(
                CaptureObjectKeys.measurement(sessionId, 0),
                new ByteArrayInputStream(PACKET.getBytes(StandardCharsets.UTF_8)),
                sha256(PACKET),
                1024);
        captureSessionUseCase.recordMeasurement(SegmentMeasurementAnnouncement.ofSegment(
                sessionId, 0, "20260908-000000-abcdef", true, Instant.parse("2026-09-08T03:00:00Z")));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("authorization", "Bearer " + TEST_API_TOKEN)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final Instant NOW = Instant.parse("2026-09-08T02:00:00Z");

    /** The manifest key is what {@code frames} reads; recordVideo does not set it. */
    private void jdbcManifest(UUID sessionId) {
        jdbcTemplate.update(
                "UPDATE capture_segments SET manifest_object_key = ? WHERE session_id = ? AND segment_index = 0",
                CaptureObjectKeys.manifest(sessionId, 0),
                sessionId);
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
