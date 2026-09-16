package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.MeasurementQuery;
import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.domain.SegmentQuery;
import br.com.greenv.videoapi.domain.SegmentReference;
import br.com.greenv.videoapi.domain.ServiceOrder;
import br.com.greenv.videoapi.domain.ServiceOrderDraft;
import br.com.greenv.videoapi.domain.ServiceOrderPriority;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.OperationsUseCase;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import br.com.greenv.videoapi.service.ApplicationException;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A segment is now measured in windows, and the control plane has to keep up.
 *
 * <p>The frame extractor used to spend its whole frame budget on the first few tens of metres of
 * a segment, so most of the road it drove past was never measured. It now cuts a segment into
 * windows of about 25 m and publishes every one, and worker 2 answers with one reconstruction,
 * one packet and one announcement per window. Everything below is what that changes here: a
 * reading is a window, a window is what a list returns and what an order targets, and the segment
 * keeps a summary of its windows so the screens that were written when a segment was the unit of
 * measurement go on working.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class SegmentWindowIntegrationTest {

    private static final String TEST_API_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final Path TEST_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "greenv-windows-" + TEST_ID);

    private static final Instant CAPTURED = Instant.parse("2026-09-14T11:00:00Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.pipeline.root", () -> TEST_ROOT.resolve("pipeline").toString());
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:windows-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("greenv.places.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    CaptureSessionStore store;

    @Autowired
    CaptureSessionUseCase captures;

    @Autowired
    OperationsUseCase operations;

    @Autowired
    CaptureObjectStorage objectStorage;

    @MockitoBean
    SegmentWorkQueue taskPublisher;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * The readings feed answers for every session there is, so a leftover from the previous test
     * would be a row in this one's answer.
     */
    @org.junit.jupiter.api.BeforeEach
    void emptyTheDatabase() {
        jdbcTemplate.update("DELETE FROM service_order_events");
        jdbcTemplate.update("DELETE FROM service_order_segments");
        jdbcTemplate.update("DELETE FROM service_orders");
        jdbcTemplate.update("DELETE FROM segment_frame_readings");
        jdbcTemplate.update("DELETE FROM capture_segment_windows");
        jdbcTemplate.update("DELETE FROM capture_segments");
        jdbcTemplate.update("DELETE FROM capture_sessions");
    }

    /**
     * Two windows of one segment, each with its own packet, its own height and its own stretch of
     * road.
     */
    @Test
    void recordsAWindowPerAnnouncementRatherThanOnePerSegment() {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.12, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 1, 25.0, 50.0, 0.48, "20260914-000000-bbbbbb");

        List<CaptureSegmentDocument> windows = store.findWindows(sessionId, 0);

        assertThat(windows).extracting(CaptureSegmentDocument::windowIndex).containsExactly(0, 1);
        assertThat(windows).extracting(w -> w.measurement().extent95P95M()).containsExactly(0.12, 0.48);
        assertThat(windows).extracting(CaptureSegmentDocument::windowStartMeters).containsExactly(0.0, 25.0);
        assertThat(windows).extracting(CaptureSegmentDocument::windowEndMeters).containsExactly(25.0, 50.0);
        assertThat(windows)
                .as("a window's packet is at its own prefix, derived here and never taken from the message")
                .extracting(CaptureSegmentDocument::measurementObjectKey)
                .containsExactly(
                        CaptureObjectKeys.measurement(sessionId, 0, 0),
                        CaptureObjectKeys.measurement(sessionId, 0, 1));
    }

    /**
     * The segment goes on answering, as a summary of its windows.
     *
     * <p>The session screen, the per-segment counters and the level chips were all written when a
     * segment was the unit of measurement. Rather than teach each of them about windows, the
     * segment reports the worst window it has — the tallest grass in it, which is what decides
     * whether a crew goes — with the cell counts summed across every window.
     */
    @Test
    void rollsTheWorstWindowUpOntoTheSegment() {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.48, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 1, 25.0, 50.0, 0.12, "20260914-000000-bbbbbb");
        givenAMeasuredWindow(sessionId, 2, 50.0, 75.0, 0.06, "20260914-000000-cccccc");

        CaptureSegmentDocument segment = store.getSegment(sessionId, 0);

        assertThat(segment.measurementState()).isEqualTo("measured");
        assertThat(segment.measurement().extent95P95M())
                .as("the worst window, not the average and not the last one announced")
                .isEqualTo(0.48);
        assertThat(segment.measurement().extent95MaxM()).isEqualTo(0.48);
        assertThat(segment.measurement().level()).isEqualTo(3);
        assertThat(segment.measurement().cellsMeasured())
                .as("summed, because the cells of three windows are three sets of cells")
                .isEqualTo(6);
        assertThat(segment.measurement().cellsAbstained()).isEqualTo(3);
        assertThat(segment.measuredAt()).isEqualTo(CAPTURED.plusSeconds(2));
        assertThat(segment.windowCount()).isEqualTo(3);
        assertThat(segment.measuredWindowCount()).isEqualTo(3);
        assertThat(segment.measurementObjectKey())
                .as("a segment cut into windows has no packet of its own; a link here would 409")
                .isNull();
    }

    /** The worker republishes the same result when a delivery is retried. */
    @Test
    void recordingTheSameWindowTwiceChangesNothing() {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.48, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.48, "20260914-000000-aaaaaa");

        assertThat(store.findWindows(sessionId, 0)).hasSize(1);
        assertThat(store.getSegment(sessionId, 0).measurement().cellsMeasured())
                .as("a redelivery must not double the cell counts it rolls up")
                .isEqualTo(2);
    }

    /**
     * The readings feed lists windows, and a segment measured whole still appears as one row.
     *
     * <p>The two have to share one ordered, paged answer: sorting each half on its own and
     * interleaving them in the browser is exactly the defect that moving the sort into SQL
     * existed to remove.
     */
    @Test
    void listsOneReadingPerWindowAndOnePerSegmentMeasuredWhole() {
        UUID windowed = givenASegment();
        givenAMeasuredWindow(windowed, 0, 0.0, 25.0, 0.12, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(windowed, 1, 25.0, 50.0, 0.48, "20260914-000000-bbbbbb");

        UUID whole = givenASegment();
        givenASegmentMeasuredWhole(whole, 0.31);

        List<CaptureSegmentDocument> readings =
                captures.listMeasurements(MeasurementQuery.tallestFirst()).items();

        assertThat(readings)
                .as("three readings from two segments, tallest first")
                .extracting(r -> r.measurement().extent95P95M())
                .containsExactly(0.48, 0.31, 0.12);
        assertThat(readings).extracting(CaptureSegmentDocument::windowIndex).containsExactly(1, null, 0);
        assertThat(readings)
                .as("the segment that has windows never appears on its own; its readings are its windows")
                .noneMatch(r -> r.sessionId().equals(windowed) && r.windowIndex() == null);
        assertThat(captures.summariseMeasurements(MeasurementQuery.tallestFirst()).total())
                .isEqualTo(3);
    }

    /** A session is still a list of uploads, with the shape of its window set on each row. */
    @Test
    void theSessionListStillCountsUploadedSegments() {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.12, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 1, 25.0, 50.0, 0.48, "20260914-000000-bbbbbb");

        var page = captures.listSegments(SegmentQuery.first(sessionId));

        assertThat(page.total()).as("one upload, not two readings").isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(segment -> {
            assertThat(segment.windowCount()).isEqualTo(2);
            assertThat(segment.measuredWindowCount()).isEqualTo(2);
            assertThat(segment.windowIndex()).isNull();
        });
    }

    @Test
    void servesEachWindowsPacketUnderItsOwnWindowNumber() throws Exception {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.12, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 1, 25.0, 50.0, 0.48, "20260914-000000-bbbbbb");
        String segment = "/v2/capture-sessions/" + sessionId + "/segments/0";

        assertThat(get(segment + "/measurement").statusCode())
                .as("a segment cut into windows has no packet of its own")
                .isEqualTo(409);

        HttpResponse<String> window = get(segment + "/measurement?window=1");
        assertThat(window.statusCode()).isEqualTo(200);
        assertThat(window.body())
                .as("served as stored, and it is window 1's packet and not window 0's")
                .isEqualTo(packet(0.48));

        assertThat(get(segment + "/measurement?window=7").statusCode())
                .as("a window nobody measured is refused the way an unmeasured segment is")
                .isEqualTo(409);

        String windows = get(segment + "/windows").body();
        assertThat(windows)
                .contains("\"windowIndex\":0")
                .contains("\"windowIndex\":1")
                .contains("\"windowStartMeters\":25.0")
                .as("the link a client follows is composed here, never by the client")
                .contains(segment + "/measurement?window=1");
    }

    /**
     * The reconstruction belongs to the window that was reconstructed.
     *
     * <p>Each window has a run of its own, so serving the segment's run id for all of them would
     * hand out one window's geometry under every window's name.
     */
    @Test
    void servesEachWindowsReconstructionUnderItsOwnRun() throws Exception {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.12, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 1, 25.0, 50.0, 0.48, "20260914-000000-bbbbbb");
        String mesh = "glTF-pretend-bytes-for-window-one";
        put(CaptureObjectKeys.depthArtifact(sessionId, 0, "20260914-000000-bbbbbb", "scene.glb"), mesh);
        String segment = "/v2/capture-sessions/" + sessionId + "/segments/0";

        HttpResponse<String> download = get(segment + "/depth/scene.glb?window=1");

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(mesh);
        assertThat(download.headers().firstValue("content-disposition").orElse(""))
                .as("a folder of downloads has to say which stretch each file is")
                .contains("-segmento-0-trecho-01-scene.glb");
        assertThat(get(segment + "/depth/scene.glb?window=0").statusCode())
                .as("window 0's own run kept no reconstruction, and window 1's is not a substitute")
                .isEqualTo(409);
    }

    /** One feature per window, each drawn from its own track. */
    @Test
    void drawsTheRouteWindowByWindow() throws Exception {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.12, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 1, 25.0, 50.0, 0.48, "20260914-000000-bbbbbb");

        String track = get("/v2/capture-sessions/" + sessionId + "/track").body();

        assertThat(track)
                .contains("\"windowIndex\":0")
                .contains("\"windowIndex\":1")
                .doesNotContain("\"windowIndex\":null");
    }

    /**
     * An order against a window is justified by that window.
     *
     * <p>The segment's projection is a rollup of every window it has, so opening an order against
     * it would send a crew to the whole segment on the evidence of its worst twenty-five metres.
     */
    @Test
    void opensAnOrderAgainstOneWindowsOwnEvidence() {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.48, "20260914-000000-aaaaaa");
        givenAMeasuredWindow(sessionId, 1, 25.0, 50.0, 0.06, "20260914-000000-bbbbbb");

        ServiceOrder order = operations.openOrder(
                new ServiceOrderDraft(
                        ServiceOrderPriority.MEDIA,
                        null,
                        null,
                        null,
                        List.of(new SegmentReference(sessionId, 0, 1))),
                "tester");

        assertThat(order.targets())
                .singleElement()
                .isEqualTo(new SegmentReference(sessionId, 0, 1));
        assertThat(order.vegetationLevel())
                .as("window 1 is 6 cm of grass; the segment rolls up to window 0's 48 cm")
                .isEqualTo(1);
        assertThat(order.centreLat()).isNotNull();
    }

    @Test
    void refusesAnOrderAgainstAWindowThatWasNeverMeasured() {
        UUID sessionId = givenASegment();
        givenAMeasuredWindow(sessionId, 0, 0.0, 25.0, 0.48, "20260914-000000-aaaaaa");

        var draft = new ServiceOrderDraft(
                ServiceOrderPriority.MEDIA, null, null, null, List.of(new SegmentReference(sessionId, 0, 4)));

        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                        ApplicationException.class, () -> operations.openOrder(draft, "tester")))
                .as("refused exactly as an unmeasured segment is")
                .satisfies(failure -> assertThat(failure.code()).isEqualTo("segment-not-measured"));
    }

    /**
     * The path that must keep working byte for byte: there are packets in production measured
     * before windows existed, and their announcements carry no window at all.
     */
    @Test
    void anAnnouncementWithNoWindowStillMeasuresTheWholeSegment() {
        UUID sessionId = givenASegment();
        givenASegmentMeasuredWhole(sessionId, 0.31);

        CaptureSegmentDocument segment = store.getSegment(sessionId, 0);

        assertThat(segment.measurementObjectKey()).isEqualTo(CaptureObjectKeys.measurement(sessionId, 0));
        assertThat(segment.measurement().extent95P95M()).isEqualTo(0.31);
        assertThat(segment.windowCount()).isZero();
        assertThat(store.findWindows(sessionId, 0)).isEmpty();
    }

    /**
     * A photograph says which stretch it belongs to, rather than the screen guessing.
     *
     * <p>The frame list is an uploaded segment's — hundreds of metres of road — and a screen
     * showing one 25 m stretch has to know which pictures are its own. Each window's packet names
     * its window and carries only the fixes of the frames its own reconstruction used, so the
     * answer is in the packet and needs no geometry: without it a browser would have to decide
     * which window's line a picture was taken nearest to, and the frames at a boundary would land
     * on either side of it.
     */
    @Test
    void aFrameCarriesTheWindowThatReconstructedIt() throws Exception {
        UUID sessionId = givenASegment();
        givenAManifestListing(sessionId, 4);
        put(CaptureObjectKeys.measurement(sessionId, 0, 0), packetOfWindow(0, 1, 2));
        put(CaptureObjectKeys.measurement(sessionId, 0, 1), packetOfWindow(1, 3, 4));
        captures.recordMeasurement(new SegmentMeasurementAnnouncement(
                sessionId, 0, 0, 0.0, 25.0, "20260914-000000-aaaaaa", false, CAPTURED));
        captures.recordMeasurement(new SegmentMeasurementAnnouncement(
                sessionId, 0, 1, 25.0, 50.0, "20260914-000000-bbbbbb", false, CAPTURED.plusSeconds(1)));

        String body = get("/v2/capture-sessions/" + sessionId + "/segments/0/frames").body();

        assertThat(body)
                .as("the first two frames were reconstructed by window 0 and the last two by window 1")
                .contains("\"fileName\":\"frame-0001.jpg\"")
                .contains("\"fileName\":\"frame-0004.jpg\"");
        assertThat(windowsOfFrames(body))
                .as("one window per frame, in the order the manifest published them")
                .containsExactly(0, 0, 1, 1);
    }

    /** A segment measured whole leaves every frame without a window, and that is not a gap. */
    @Test
    void aFrameOfASegmentMeasuredWholeCarriesNoWindow() throws Exception {
        UUID sessionId = givenASegment();
        givenAManifestListing(sessionId, 2);
        givenASegmentMeasuredWhole(sessionId, 0.31);

        String body = get("/v2/capture-sessions/" + sessionId + "/segments/0/frames").body();

        assertThat(body).contains("\"windowIndex\":null");
    }

    /** The {@code windowIndex} of every frame in the listing, in the order they were published. */
    private static List<Integer> windowsOfFrames(String body) {
        String field = "\"windowIndex\":";
        List<Integer> windows = new java.util.ArrayList<>();
        for (int at = body.indexOf(field); at >= 0; at = body.indexOf(field, at + 1)) {
            String rest = body.substring(at + field.length());
            String value = rest.split("[,}]", 2)[0];
            windows.add("null".equals(value) ? null : Integer.valueOf(value));
        }
        return windows;
    }

    /** A manifest publishing `count` frames, which is the guest list the frame routes read. */
    private void givenAManifestListing(UUID sessionId, int count) {
        StringBuilder manifest = new StringBuilder("{\"sampledFrames\":[");
        for (int frame = 1; frame <= count; frame++) {
            manifest.append(frame == 1 ? "" : ",")
                    .append("{\"fileName\":\"frame-%04d.jpg\",\"sizeBytes\":64}".formatted(frame));
        }
        put(CaptureObjectKeys.manifest(sessionId, 0), manifest.append("]}").toString());
        jdbcTemplate.update(
                "UPDATE capture_segments SET manifest_object_key = ? WHERE session_id = ? AND segment_index = 0",
                CaptureObjectKeys.manifest(sessionId, 0),
                sessionId);
    }

    /** One window's envelope: it names its window and carries only its own frames' fixes. */
    private static String packetOfWindow(int windowIndex, int... canonicalFrames) {
        StringBuilder positions = new StringBuilder();
        for (int frame : canonicalFrames) {
            positions.append(positions.isEmpty() ? "" : ",")
                    .append(("{\"canonicalFrame\":%d,\"latitude\":-23.6365,\"longitude\":-46.6834,"
                                    + "\"locationQuality\":\"good\"}")
                            .formatted(frame));
        }
        return ("""
                {"schemaVersion":"greenv.measurement-result/1.0.0","runId":"r","mock":false,                "windowIndex":%d,"positions":[%s],                "measurement":{"quality":{"measuredCells":2,"abstainedCells":1,                "observedCellCoverage":0.667,"operationalStatus":"not-ready"}}}""")
                .formatted(windowIndex, positions);
    }

    private UUID givenASegment() {
        UUID sessionId = UUID.randomUUID();
        store.createSession(new CaptureSessionDocument(
                sessionId,
                "pilot-phone",
                "recording",
                CAPTURED,
                null,
                CAPTURED,
                CAPTURED,
                CAPTURED.plusSeconds(3600),
                null,
                "BR-101",
                null));
        store.ensureSegment(sessionId, 0, "%s:0".formatted(sessionId), CAPTURED, 10_000, CAPTURED);
        return sessionId;
    }

    /**
     * One window as worker 2 leaves it: the envelope and the cell grid, under the window's own
     * prefix, then the announcement that says it is there.
     */
    private void givenAMeasuredWindow(
            UUID sessionId, int windowIndex, double startMeters, double endMeters, double height, String runId) {
        put(CaptureObjectKeys.measurement(sessionId, 0, windowIndex), packet(height));
        put(
                CaptureObjectKeys.measurementArtifact(sessionId, 0, windowIndex, "assessment.json"),
                assessment(height));
        captures.recordMeasurement(new SegmentMeasurementAnnouncement(
                sessionId,
                0,
                windowIndex,
                startMeters,
                endMeters,
                runId,
                false,
                CAPTURED.plusSeconds(windowIndex)));
    }

    private void givenASegmentMeasuredWhole(UUID sessionId, double height) {
        put(CaptureObjectKeys.measurement(sessionId, 0), packet(height));
        put(CaptureObjectKeys.measurementArtifact(sessionId, 0, "assessment.json"), assessment(height));
        captures.recordMeasurement(SegmentMeasurementAnnouncement.ofSegment(
                sessionId, 0, "20260914-000000-dddddd", false, CAPTURED.plusSeconds(5)));
    }

    /** Shaped after the packets in storage: two measured cells, one that abstained, two fixes. */
    private static String packet(double height) {
        return ("""
                {"schemaVersion":"greenv.measurement-result/1.0.0","runId":"r","mock":false,\
                "positions":[\
                {"canonicalFrame":1,"latitude":-23.6365,"longitude":-46.6834,"locationQuality":"good"},\
                {"canonicalFrame":2,"latitude":-23.6366,"longitude":-46.6835,"locationQuality":"good"}],\
                "measurement":{"quality":{"measuredCells":2,"abstainedCells":1,\
                "observedCellCoverage":0.667,"operationalStatus":"not-ready"},"height":%s}}""")
                .formatted(height);
    }

    private static String assessment(double height) {
        return ("{\"assessment\":{\"measurements\":["
                        + "{\"status\":\"measured\",\"extent95M\":%s},"
                        + "{\"status\":\"measured\",\"extent95M\":%s}]}}")
                .formatted(height, height);
    }

    private void put(String objectKey, String body) {
        objectStorage.put(
                objectKey,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                sha256(body),
                1024 * 1024);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .header("authorization", "Bearer " + TEST_API_TOKEN)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
