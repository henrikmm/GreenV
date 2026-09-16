package br.com.greenv.videoapi.storage;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.MeasurementQuery;
import br.com.greenv.videoapi.domain.MeasurementSort;
import br.com.greenv.videoapi.domain.MeasurementSummary;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SegmentQuery;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ordering and paging a list of readings, against a database rather than a mock.
 *
 * <p>This is the half that cannot be proved with a stub. The dashboard used to ask for two hundred
 * rows and sort them in the browser, which is the right answer only while everything fits in one
 * request; past that, page one is the tallest of an arbitrary slice and looks exactly like the
 * tallest there is. Moving the sort into SQL is only a fix if the SQL is right, and the parts most
 * likely to be wrong — where nulls land, whether a tie is stable across pages, whether a day
 * boundary is half-open — are all invisible until a query runs.
 *
 * <p>It also pins the migration. V13 declares its height index {@code DESC NULLS LAST}, and if H2
 * in PostgreSQL mode refused that syntax, every test in the suite would fail at Flyway rather than
 * here — which is the point of running the suite on the same dialect.
 */
@SpringBootTest(properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class JdbcMeasurementQueryIntegrationTest {

    private static final String TEST_ID = UUID.randomUUID().toString();

    /** Three moments, two of them inside the same local day and one the day before. */
    private static final Instant NOON = Instant.parse("2026-09-11T15:00:00Z");
    private static final Instant LATER = NOON.plus(2, ChronoUnit.HOURS);
    private static final Instant YESTERDAY = NOON.minus(1, ChronoUnit.DAYS);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:measurement-query-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("greenv.places.enabled", () -> "false");
    }

    @Autowired
    CaptureSessionStore store;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final UUID session = UUID.fromString("01a089bf-0541-7171-8b15-86fee795894a");
    private final UUID otherSession = UUID.fromString("01a09274-8b0c-71b8-8aa9-59a766b50711");

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM capture_segments");
        jdbcTemplate.update("DELETE FROM capture_sessions");
        insertSession(session);
        insertSession(otherSession);

        // Heights on purpose: two equal at 0.40 in different sessions, to see whether a tie is
        // stable, and one measured segment with no height at all.
        insertSegment(session, 0, NOON, 3, 0.90, "Avenida Armando Ferrentini");
        insertSegment(session, 1, LATER, 2, 0.40, "Rua do Paraíso");
        insertSegment(otherSession, 0, LATER, 2, 0.40, "Rua Topazio");
        insertSegment(otherSession, 1, YESTERDAY, 1, 0.05, "Jardim dos Estados");
        insertSegment(otherSession, 2, YESTERDAY, null, null, "Rua Braz Cubas");

        // Recorded but never measured. Nothing in this list may ever return it.
        insertUnmeasured(otherSession, 3, NOON);
    }

    @Test
    void putsTheTallestFirstAndTheUnmeasurableLast() {
        List<Double> heights = heightsOf(query(MeasurementSort.HEIGHT_DESC, 10, 0));

        // Not 0.90, 0.40, 0.40, 0.05, then null. A reading nobody could measure is not a short
        // one, and sorted as zero it would sit among the trimmed verges.
        assertThat(heights).containsExactly(0.90, 0.40, 0.40, 0.05, null);
    }

    @Test
    void keepsTheUnmeasurableLastGoingUpTheOtherWay() {
        assertThat(heightsOf(query(MeasurementSort.HEIGHT_ASC, 10, 0)))
                .containsExactly(0.05, 0.40, 0.40, 0.90, null);
    }

    @Test
    void neverReturnsASegmentThatWasNeverMeasured() {
        assertThat(query(MeasurementSort.HEIGHT_DESC, 10, 0))
                .noneMatch(segment -> segment.segmentIndex() == 3 && segment.sessionId().equals(otherSession));
    }

    /**
     * The failure that paging exists to avoid: two rows of equal height swapping between requests,
     * so one shows up twice and another never does.
     */
    @Test
    void doesNotLetATieSwapBetweenPages() {
        for (int attempt = 0; attempt < 5; attempt++) {
            List<String> firstThree = keysOf(query(MeasurementSort.HEIGHT_DESC, 3, 0));
            List<String> nextTwo = keysOf(query(MeasurementSort.HEIGHT_DESC, 3, 3));

            assertThat(firstThree).doesNotContainAnyElementsOf(nextTwo);
            assertThat(firstThree).hasSize(3);
            assertThat(nextTwo).hasSize(2);
        }
    }

    @Test
    void reportsTheWholeTotalOnEveryPage() {
        Page<CaptureSegmentDocument> page = store.findMeasurements(
                new MeasurementQuery(MeasurementSort.HEIGHT_DESC, null, null, null, null, 2, 2));

        assertThat(page.items()).hasSize(2);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.hasMore()).isTrue();
    }

    /** Half-open, so the reading at the boundary belongs to one day and not to both. */
    @Test
    void boundsTheDayWithAnExclusiveUpperEdge() {
        List<CaptureSegmentDocument> justNoon = store.findMeasurements(new MeasurementQuery(
                        MeasurementSort.HEIGHT_DESC, null, NOON, LATER, null, 10, 0))
                .items();

        assertThat(heightsOf(justNoon)).containsExactly(0.90);
    }

    @Test
    void filtersByLevelAndCountsTheUnclassifiedAsZeroRatherThanLow() {
        assertThat(heightsOf(store.findMeasurements(new MeasurementQuery(
                                MeasurementSort.HEIGHT_DESC, 2, null, null, null, 10, 0))
                        .items()))
                .containsExactly(0.40, 0.40);

        // The segment with no level at all, which is "not classified" and not "below ten".
        assertThat(heightsOf(store.findMeasurements(new MeasurementQuery(
                                MeasurementSort.HEIGHT_DESC, 0, null, null, null, 10, 0))
                        .items()))
                .containsExactly((Double) null);
    }

    /**
     * Shipped wrong once, and invisibly: the column held "Rua do Paraíso" and a reader typing
     * "paraiso" got nothing back, which reads as "that street was never measured" rather than as
     * a search that cannot spell. Half the street names in the register carry an accent and
     * nobody types them.
     */
    @Test
    void findsAnAccentedStreetFromAnUnaccentedSearch() {
        assertThat(heightsOf(store.findMeasurements(new MeasurementQuery(
                                MeasurementSort.HEIGHT_DESC, null, null, null, "paraiso", 10, 0))
                        .items()))
                .containsExactly(0.40);
    }

    @Test
    void stillFindsItWhenTheAccentIsTypedAndTheCaseIsWrong() {
        assertThat(heightsOf(store.findMeasurements(new MeasurementQuery(
                                MeasurementSort.HEIGHT_DESC, null, null, null, "PARAÍSO", 10, 0))
                        .items()))
                .containsExactly(0.40);
    }

    /**
     * The counters answer for the filtered set, not for the page. A count that described only the
     * page would be an answer about the request rather than about the data.
     */
    @Test
    void countsTheWholeFilteredSetRatherThanThePage() {
        MeasurementSummary summary = store.summariseMeasurements(
                new MeasurementQuery(MeasurementSort.HEIGHT_DESC, null, null, null, null, 2, 0));

        assertThat(summary.total()).isEqualTo(5);
        // Sem ordem: a map has none, and asserting one tests the assertion, not the query.
        assertThat(summary.countsByLevel())
                .containsOnlyKeys(0, 1, 2, 3)
                .containsEntry(0, 1L)
                .containsEntry(1, 1L)
                .containsEntry(2, 2L)
                .containsEntry(3, 1L);
        assertThat(summary.tallestM()).isEqualTo(0.90);
    }

    /**
     * The quality of the fixes is part of the reading, so its tally belongs to the same aggregate.
     *
     * <p>Counted in the browser it would describe the page, and the screen would quietly report
     * the precision of twenty-five readings as the precision of the day.
     */
    @Test
    void countsTheReadingsByTheQualityOfTheFixesBehindThem() {
        MeasurementSummary summary = store.summariseMeasurements(
                new MeasurementQuery(MeasurementSort.HEIGHT_DESC, null, null, null, null, 2, 0));

        assertThat(summary.countsByLocationQuality())
                .containsOnlyKeys("good", "degraded", "unavailable")
                .containsEntry("good", 1L)
                .containsEntry("degraded", 3L)
                // The reading with no level never got a track either, which is neither good nor
                // degraded — and it has to land somewhere, or the buckets would not add up.
                .containsEntry("unavailable", 1L);
        assertThat(summary.countsByLocationQuality().values().stream().mapToLong(Long::longValue).sum())
                .as("every reading falls in exactly one bucket")
                .isEqualTo(summary.total());
    }

    @Test
    void countsPerLevelIgnoreTheLevelFilterSoEveryChipHasANumber() {
        MeasurementSummary summary = store.summariseMeasurements(
                new MeasurementQuery(MeasurementSort.HEIGHT_DESC, 3, null, null, null, 25, 0));

        assertThat(summary.total()).isEqualTo(5);
        assertThat(summary.countsByLevel()).containsEntry(2, 2L);
    }

    @Test
    void answersWithNoTallestWhenTheFilterSelectsNothing() {
        MeasurementSummary summary = store.summariseMeasurements(new MeasurementQuery(
                MeasurementSort.HEIGHT_DESC, null, null, null, "rua que nao existe", 25, 0));

        assertThat(summary.total()).isZero();
        assertThat(summary.tallestM()).isNull();
    }

    /**
     * A session is read as a sequence, so its page is ordered by index and never by height.
     *
     * <p>It also has to include the segment that was never measured: the readings feed is about
     * what was measured, but a session's own list is about what was recorded, and a stretch the
     * pipeline has not reached yet is exactly what someone opens a session to find.
     */
    @Test
    void pagesOneSessionInCaptureOrderIncludingWhatWasNotMeasured() {
        Page<CaptureSegmentDocument> first = store.findSegments(
                new SegmentQuery(otherSession, null, 2, 0));
        Page<CaptureSegmentDocument> second = store.findSegments(
                new SegmentQuery(otherSession, null, 2, 2));

        assertThat(first.items()).extracting(CaptureSegmentDocument::segmentIndex).containsExactly(0, 1);
        assertThat(second.items()).extracting(CaptureSegmentDocument::segmentIndex).containsExactly(2, 3);
        assertThat(first.total()).isEqualTo(4);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void filtersOneSessionByLevel() {
        assertThat(store.findSegments(new SegmentQuery(otherSession, 2, 25, 0)).items())
                .extracting(CaptureSegmentDocument::segmentIndex)
                .containsExactly(0);
    }

    @Test
    void countsEveryLevelInTheSessionRatherThanOnThePage() {
        MeasurementSummary summary = store.summariseSegments(otherSession);

        // Four segments: one level 2, one level 1, one measured with no level, one never measured.
        assertThat(summary.total()).isEqualTo(4);
        assertThat(summary.countsByLevel())
                .containsEntry(0, 2L)
                .containsEntry(1, 1L)
                .containsEntry(2, 1L)
                .containsEntry(3, 0L);
    }

    private List<CaptureSegmentDocument> query(MeasurementSort sort, int limit, int offset) {
        return store.findMeasurements(new MeasurementQuery(sort, null, null, null, null, limit, offset))
                .items();
    }

    private static List<Double> heightsOf(List<CaptureSegmentDocument> segments) {
        return segments.stream().map(segment -> segment.measurement().extent95P95M()).toList();
    }

    private static List<String> keysOf(List<CaptureSegmentDocument> segments) {
        return segments.stream().map(s -> s.sessionId() + ":" + s.segmentIndex()).toList();
    }

    private void insertSession(UUID sessionId) {
        jdbcTemplate.update(
                """
                INSERT INTO capture_sessions
                    (session_id, device_id, state, started_at, created_at, updated_at, expires_at)
                VALUES (?, 'greenv-test', 'closed', ?, ?, ?, ?)
                """,
                sessionId,
                Timestamp.from(NOON),
                Timestamp.from(NOON),
                Timestamp.from(NOON),
                Timestamp.from(NOON.plus(30, ChronoUnit.DAYS)));
    }

    private void insertSegment(UUID sessionId, int index, Instant capturedAt, Integer level, Double height, String place) {
        insertUnmeasured(sessionId, index, capturedAt);
        jdbcTemplate.update(
                """
                UPDATE capture_segments
                   SET measurement_state = 'measured', measured_at = ?,
                       measurement_level = ?, measurement_extent95_p95_m = ?, place_label = ?,
                       track_location_quality = CASE WHEN ? = 1 THEN 'good'
                                                     WHEN ? IS NULL THEN NULL
                                                     ELSE 'degraded' END
                 WHERE session_id = ? AND segment_index = ?
                """,
                Timestamp.from(capturedAt.plus(1, ChronoUnit.HOURS)),
                level,
                height,
                place,
                level,
                level,
                sessionId,
                index);
    }

    private void insertUnmeasured(UUID sessionId, int index, Instant capturedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO capture_segments
                    (session_id, segment_index, state, idempotency_key, captured_at,
                     duration_millis, created_at, updated_at)
                VALUES (?, ?, 'ready', ?, ?, 10000, ?, ?)
                """,
                sessionId,
                index,
                sessionId + "-" + index,
                Timestamp.from(capturedAt),
                Timestamp.from(capturedAt),
                Timestamp.from(capturedAt));
    }
}
