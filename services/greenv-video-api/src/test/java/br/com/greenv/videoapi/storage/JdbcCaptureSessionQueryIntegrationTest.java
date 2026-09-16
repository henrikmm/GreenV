package br.com.greenv.videoapi.storage;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.domain.CaptureSessionSort;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SessionPlace;
import br.com.greenv.videoapi.domain.SessionReadingCounts;
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
 * Filtering, counting and ordering a list of sessions, against a database rather than a mock.
 *
 * <p>The sessions screen used to fetch one page of fifty and then pick a day and an order out of
 * it in the browser. That answers about the request rather than about the data — the defect the
 * readings list was moved into SQL to remove — and it is worse on this screen, because a day
 * filter that removes every row of the page it was given leaves a reader looking at an empty
 * list of a capture that exists.
 *
 * <p>What is checked here is what only a running query can show: which side of a day boundary a
 * session lands on, whether the level counters count windows or the segments they came from, and
 * whether an order with ties survives being paged.
 */
@SpringBootTest(properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class JdbcCaptureSessionQueryIntegrationTest {

    private static final String TEST_ID = UUID.randomUUID().toString();

    /** Two moments inside one local day, and one the day before. */
    private static final Instant NOON = Instant.parse("2026-09-11T15:00:00Z");
    private static final Instant LATER = NOON.plus(2, ChronoUnit.HOURS);
    private static final Instant YESTERDAY = NOON.minus(1, ChronoUnit.DAYS);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:session-query-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("greenv.places.enabled", () -> "false");
    }

    @Autowired
    CaptureSessionStore store;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /** One segment cut into windows: its readings are the windows and never the segment. */
    private final UUID windowed = UUID.fromString("01a089bf-0541-7171-8b15-86fee795894a");

    /** Segments the extractor never cut, from before windows existed. */
    private final UUID whole = UUID.fromString("01a09274-8b0c-71b8-8aa9-59a766b50711");

    /** Two sessions started in the same millisecond, so only the tie-break separates them. */
    private final UUID overgrownYesterday = UUID.fromString("01a0a11a-1111-7111-8111-111111111111");
    private final UUID trimmedYesterday = UUID.fromString("01a0a22b-2222-7222-8222-222222222222");

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM capture_segment_windows");
        jdbcTemplate.update("DELETE FROM capture_segments");
        jdbcTemplate.update("DELETE FROM capture_sessions");

        // One upload, three measured stretches. The segment carries the rollup — level 3, the
        // worst of its windows — and counting it as well would report four readings for three.
        insertSession(windowed, NOON);
        insertSegment(windowed, 0, NOON, 3);
        insertWindow(windowed, 0, 0, 3);
        insertWindow(windowed, 0, 1, 3);
        insertWindow(windowed, 0, 2, 1);

        // Three uploads, no windows: two readings with a level and one the pipeline never
        // reached, which is unrated rather than absent.
        insertSession(whole, LATER);
        insertSegment(whole, 0, LATER, 3);
        insertSegment(whole, 1, LATER, 2);
        insertUnmeasured(whole, 2, LATER);

        insertSession(overgrownYesterday, YESTERDAY);
        insertSegment(overgrownYesterday, 0, YESTERDAY, 2);
        insertSegment(overgrownYesterday, 1, YESTERDAY, 2);

        insertSession(trimmedYesterday, YESTERDAY);
        insertSegment(trimmedYesterday, 0, YESTERDAY, 1);
    }

    @Test
    void ordersNewestFirstWhenNothingIsAsked() {
        assertThat(idsOf(store.findSessions(CaptureSessionQuery.recent())))
                .startsWith(whole, windowed)
                .hasSize(4);
    }

    /**
     * Half-open, so a session started exactly at midnight belongs to the day that begins there
     * and not to both of the days that touch it.
     */
    @Test
    void includesTheLowerBoundOfADayAndExcludesTheUpper() {
        // The bounds are two sessions' own start instants, which is the edge case a date picker
        // produces every time a capture begins on the hour it asks about.
        assertThat(idsOf(sessions(NOON, LATER, CaptureSessionSort.STARTED_DESC, 10, 0)))
                .containsExactly(windowed);

        assertThat(idsOf(sessions(YESTERDAY, NOON, CaptureSessionSort.STARTED_DESC, 10, 0)))
                .containsExactlyInAnyOrder(overgrownYesterday, trimmedYesterday);
    }

    @Test
    void countsTheWholeFilteredSetAndNotThePage() {
        Page<CaptureSessionSummary> page = sessions(YESTERDAY, LATER, CaptureSessionSort.STARTED_DESC, 1, 0);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasMore()).isTrue();
    }

    /**
     * The order the screen exists for: where to send a crew, not which capture is newest.
     *
     * <p>Level 3 decides, level 2 breaks the tie. Not the tallest single reading — one 90 cm
     * window on an otherwise trimmed verge is an hour of work, and two overdue stretches are a
     * morning of it.
     */
    @Test
    void putsTheSessionWithTheMostLevelThreeReadingsFirst() {
        assertThat(idsOf(sessions(null, null, CaptureSessionSort.CRITICAL_DESC, 10, 0)))
                .containsExactly(
                        windowed, // two level-3 windows
                        whole, // one level-3 segment
                        overgrownYesterday, // no level 3, two level 2
                        trimmedYesterday); // no level 3, no level 2
    }

    /**
     * A reading is a window where there are windows, and the segment itself where there are none.
     *
     * <p>Counted any other way the sessions screen and the readings screen disagree about the
     * same capture: the session line would say four readings and the list behind it would show
     * three.
     */
    @Test
    void countsWindowsForAWindowedSegmentAndTheSegmentItselfForOneWithout() {
        assertThat(readingsOf(windowed)).isEqualTo(new SessionReadingCounts(1, 0, 2, 0));
        assertThat(readingsOf(whole)).isEqualTo(new SessionReadingCounts(0, 1, 1, 1));
    }

    /** The single-session route answers what the list said it would, from the same source. */
    @Test
    void reportsTheSameCountsForOneSessionAsForItsLineInTheList() {
        assertThat(store.readingCounts(windowed)).isEqualTo(readingsOf(windowed));
        assertThat(store.readingCounts(whole)).isEqualTo(readingsOf(whole));
    }

    /** A session recorded this morning has no reading yet, and still has to appear. */
    @Test
    void keepsASessionThatHasNoReadingAtAll() {
        UUID empty = UUID.fromString("01a0a33c-3333-7333-8333-333333333333");
        insertSession(empty, LATER);

        assertThat(idsOf(store.findSessions(CaptureSessionQuery.recent()))).contains(empty);
        assertThat(store.readingCounts(empty)).isEqualTo(SessionReadingCounts.EMPTY);
    }

    /**
     * The failure paging exists to avoid: two rows that compare equal swapping between requests,
     * so one shows up twice and another never does.
     *
     * <p>Two of these sessions started in the same millisecond, which is what a capture app that
     * uploads a backlog actually produces.
     */
    @Test
    void doesNotLetTwoSessionsSwapBetweenPages() {
        for (CaptureSessionSort sort : CaptureSessionSort.values()) {
            for (int attempt = 0; attempt < 5; attempt++) {
                List<UUID> first = idsOf(sessions(null, null, sort, 2, 0));
                List<UUID> second = idsOf(sessions(null, null, sort, 2, 2));

                assertThat(first).hasSize(2);
                assertThat(second).hasSize(2);
                assertThat(first).doesNotContainAnyElementsOf(second);
            }
        }
    }

    @Test
    void stillFiltersByRoadAndByWhatWasMeasured() {
        jdbcTemplate.update("UPDATE capture_sessions SET rodovia = 'SP-270' WHERE session_id = ?", whole);

        assertThat(idsOf(store.findSessions(new CaptureSessionQuery(
                        null, "SP-270", null, false, null, null, CaptureSessionSort.STARTED_DESC, 10, 0))))
                .containsExactly(whole);

        UUID unmeasured = UUID.fromString("01a0a44d-4444-7444-8444-444444444444");
        insertSession(unmeasured, LATER);
        insertUnmeasured(unmeasured, 0, LATER);

        assertThat(idsOf(store.findSessions(new CaptureSessionQuery(
                        null, null, null, true, null, null, CaptureSessionSort.STARTED_DESC, 10, 0))))
                .doesNotContain(unmeasured)
                .hasSize(4);
    }

    private SessionPlace placeOf(UUID sessionId) {
        return store.findSessions(CaptureSessionQuery.recent()).items().stream()
                .filter(summary -> summary.session().sessionId().equals(sessionId))
                .map(CaptureSessionSummary::place)
                .findFirst()
                .orElseThrow();
    }

    private SessionReadingCounts readingsOf(UUID sessionId) {
        return store.findSessions(CaptureSessionQuery.recent()).items().stream()
                .filter(summary -> summary.session().sessionId().equals(sessionId))
                .map(CaptureSessionSummary::readings)
                .findFirst()
                .orElseThrow();
    }

    private Page<CaptureSessionSummary> sessions(
            Instant from, Instant to, CaptureSessionSort sort, int limit, int offset) {
        return store.findSessions(
                new CaptureSessionQuery(null, null, null, false, from, to, sort, limit, offset));
    }

    private static List<UUID> idsOf(Page<CaptureSessionSummary> page) {
        return page.items().stream().map(summary -> summary.session().sessionId()).toList();
    }

    private void insertSession(UUID sessionId, Instant startedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO capture_sessions
                    (session_id, device_id, state, started_at, created_at, updated_at, expires_at)
                VALUES (?, 'greenv-test', 'ready', ?, ?, ?, ?)
                """,
                sessionId,
                Timestamp.from(startedAt),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plus(30, ChronoUnit.DAYS)));
    }

    /**
     * The first reading names the session, and the rest say how far it travelled.
     *
     * <p>A drive of two hundred metres crosses junctions, so the street of one reading is not the
     * street of the session. The line reports the first one in capture order and how many there
     * were, which is what lets a screen add "and one more" instead of pretending.
     */
    @Test
    void namesASessionAfterItsFirstReadingAndCountsTheStreetsItCrossed() {
        placeWindow(windowed, 0, 1, "Rua das Palmeiras", "em frente ao 120");
        placeWindow(windowed, 0, 2, "Avenida Brasil", null);

        var place = placeOf(windowed);

        assertThat(place.label())
                .as("window 1 is the first that resolved, and window 2 came after it")
                .isEqualTo("Rua das Palmeiras");
        assertThat(place.detail()).isEqualTo("em frente ao 120");
        assertThat(place.distinctLabels()).isEqualTo(2);
        assertThat(store.readingPlace(windowed))
                .as("one session alone answers what its line in the list answered")
                .isEqualTo(place);
    }

    /** No street is an answer. Inventing one from the session next to it would not be. */
    @Test
    void reportsNoPlaceForASessionWhoseReadingsResolvedNone() {
        var place = placeOf(whole);

        assertThat(place.label()).isNull();
        assertThat(place.detail()).isNull();
        assertThat(place.distinctLabels()).isZero();
        assertThat(store.readingPlace(whole)).isEqualTo(SessionPlace.NOWHERE);
    }

    private void insertSegment(UUID sessionId, int index, Instant capturedAt, Integer level) {
        insertUnmeasured(sessionId, index, capturedAt);
        jdbcTemplate.update(
                """
                UPDATE capture_segments
                   SET measurement_state = 'measured', measured_at = ?, measurement_level = ?
                 WHERE session_id = ? AND segment_index = ?
                """,
                Timestamp.from(capturedAt.plus(1, ChronoUnit.HOURS)),
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

    /** Gives one window a resolved street, the way the geocoder does. */
    private void placeWindow(UUID sessionId, int segmentIndex, int windowIndex, String label, String detail) {
        jdbcTemplate.update(
                """
                UPDATE capture_segment_windows
                   SET place_label = ?, place_detail = ?, place_resolved_at = ?
                 WHERE session_id = ? AND segment_index = ? AND window_index = ?
                """,
                label,
                detail,
                Timestamp.from(NOON),
                sessionId,
                segmentIndex,
                windowIndex);
    }

    private void insertWindow(UUID sessionId, int segmentIndex, int windowIndex, Integer level) {
        jdbcTemplate.update(
                """
                INSERT INTO capture_segment_windows
                    (session_id, segment_index, window_index, start_meters, end_meters,
                     measurement_state, measured_at, measurement_level, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'measured', ?, ?, ?, ?)
                """,
                sessionId,
                segmentIndex,
                windowIndex,
                windowIndex * 25.0,
                (windowIndex + 1) * 25.0,
                Timestamp.from(NOON),
                level,
                Timestamp.from(NOON),
                Timestamp.from(NOON));
    }
}
