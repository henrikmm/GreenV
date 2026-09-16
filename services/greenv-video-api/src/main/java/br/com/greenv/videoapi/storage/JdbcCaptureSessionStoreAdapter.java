package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.MeasurementProjection;
import br.com.greenv.videoapi.domain.MeasurementQuery;
import br.com.greenv.videoapi.domain.MeasurementSummary;
import br.com.greenv.videoapi.domain.FrameReadings;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SegmentPlace;
import br.com.greenv.videoapi.domain.SegmentQuery;
import br.com.greenv.videoapi.domain.Sentido;
import br.com.greenv.videoapi.domain.SessionPlace;
import br.com.greenv.videoapi.domain.SessionReadingCounts;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcCaptureSessionStoreAdapter implements CaptureSessionStore {

    /**
     * How many windows a segment was cut into, and how many of them carry a reading.
     *
     * <p>Two correlated counts rather than a join, because they hang off every shape below —
     * a segment row, a window row, a row of the readings union — and a join would have to be
     * written three times and grouped three ways.
     */
    private static final String WINDOW_COUNTS = """
            (SELECT COUNT(*) FROM capture_segment_windows c
              WHERE c.session_id = g.session_id AND c.segment_index = g.segment_index)
                AS window_count,
            (SELECT COUNT(*) FROM capture_segment_windows c
              WHERE c.session_id = g.session_id AND c.segment_index = g.segment_index
                AND c.measurement_state IS NOT NULL) AS measured_window_count
            """;

    /**
     * What was uploaded. Always the segment's, because a window is a stretch of one upload and
     * never an upload of its own.
     */
    private static final String UPLOAD_COLUMNS = """
            g.session_id, g.segment_index, g.state, g.idempotency_key, g.captured_at,
            g.duration_millis, g.video_object_key, g.video_sha256, g.video_bytes,
            g.telemetry_object_key, g.telemetry_sha256, g.telemetry_bytes,
            g.manifest_object_key, g.frame_count, g.error_code, g.error_message
            """;

    /**
     * What was measured, from whichever table holds the reading: {@code g} for a segment measured
     * whole, {@code w} for one window. The two tables spell these columns identically on purpose,
     * which is what lets one mapper read either.
     */
    private static final String MEASUREMENT_COLUMNS = """
            %1$s.measurement_state, %1$s.measurement_object_key, %1$s.measurement_run_id,
            %1$s.measurement_is_mock, %1$s.measured_at, %1$s.measurement_extent95_p95_m,
            %1$s.measurement_extent95_max_m, %1$s.measurement_level,
            %1$s.measurement_cells_measured, %1$s.measurement_cells_abstained,
            %1$s.measurement_coverage, %1$s.measurement_track_length_m,
            %1$s.track_center_lat, %1$s.track_center_lon, %1$s.track_geojson,
            %1$s.track_location_quality, %1$s.place_label, %1$s.place_detail,
            %1$s.place_house_number, %1$s.place_road, %1$s.place_km, %1$s.place_km_offset_m,
            %1$s.place_source, %1$s.place_resolved_at, %1$s.created_at, %1$s.updated_at
            """;

    /** One uploaded segment, with the size of its window set beside it. */
    private static final String SEGMENT_SELECT = "SELECT g.*, "
            + "CAST(NULL AS INTEGER) AS window_index, "
            + "CAST(NULL AS DOUBLE PRECISION) AS window_start_meters, "
            + "CAST(NULL AS DOUBLE PRECISION) AS window_end_meters, "
            + WINDOW_COUNTS
            + " FROM capture_segments g";

    private static final String WINDOW_JOIN = """
             FROM capture_segment_windows w
             JOIN capture_segments g
               ON g.session_id = w.session_id AND g.segment_index = w.segment_index
            """;

    /** One window, wearing its segment's upload fields so one mapper reads both. */
    private static final String WINDOW_SELECT = "SELECT " + UPLOAD_COLUMNS + ", "
            + MEASUREMENT_COLUMNS.formatted("w")
            + ", w.window_index, w.start_meters AS window_start_meters, "
            + "w.end_meters AS window_end_meters, "
            + WINDOW_COUNTS
            + WINDOW_JOIN;

    /**
     * Every reading there is, at the scale it was taken.
     *
     * <p>One row per measured window, and one row per segment that has no windows — which is every
     * segment measured before the extractor started cutting them, and any whose frames made a
     * single window. A segment that does have windows never appears on its own: its readings are
     * its windows, and counting it as well would double every total on the screen.
     *
     * <p>A derived table rather than two queries merged in Java, because the ordering, the paging
     * and the counters all have to happen over the whole set. Sorting one page of each half and
     * interleaving them in memory is the defect that moving the sort into SQL existed to remove.
     */
    private static final String READING_SOURCE = "(SELECT " + UPLOAD_COLUMNS + ", "
            + MEASUREMENT_COLUMNS.formatted("g")
            + ", CAST(NULL AS INTEGER) AS window_index"
            + ", CAST(NULL AS DOUBLE PRECISION) AS window_start_meters"
            + ", CAST(NULL AS DOUBLE PRECISION) AS window_end_meters, "
            + WINDOW_COUNTS
            + " FROM capture_segments g"
            + " WHERE NOT EXISTS (SELECT 1 FROM capture_segment_windows c"
            + "                    WHERE c.session_id = g.session_id"
            + "                      AND c.segment_index = g.segment_index)"
            + " UNION ALL "
            + WINDOW_SELECT
            + ") reading";

    /**
     * One row per session, carrying how many of its readings fall in each level.
     *
     * <p>Over {@link #READING_SOURCE} and nothing else, because the sessions screen links
     * straight to the readings screen: counting a windowed segment as one row here and as eight
     * rows there would make the two pages contradict each other on the same data.
     *
     * <p>Grouped once and joined, rather than four correlated counts per row. The sessions list
     * sorts on these, so they have to exist before the ORDER BY runs; a subquery repeated in the
     * select list and again in the ordering would be eight scans to draw fifty lines.
     */
    private static final String SESSION_READING_COUNTS = """
            (SELECT session_id,
                    SUM(CASE WHEN measurement_level = 1 THEN 1 ELSE 0 END) AS level_1,
                    SUM(CASE WHEN measurement_level = 2 THEN 1 ELSE 0 END) AS level_2,
                    SUM(CASE WHEN measurement_level = 3 THEN 1 ELSE 0 END) AS level_3,
                    SUM(CASE WHEN measurement_level IN (1, 2, 3) THEN 0 ELSE 1 END) AS level_0
               FROM """
            + READING_SOURCE
            + " GROUP BY session_id) r";

    /**
     * Where each session was, from its own readings.
     *
     * <p>The first geocoded reading in capture order names the session, and the count of
     * distinct streets beside it is what lets a screen say the drive crossed more than one.
     * Ranking here rather than in Java keeps it in the one query the list already runs, and
     * keeps this screen's answer identical to the readings screen's.
     */
    private static final String SESSION_PLACES =
            "(SELECT session_id, place_label, place_detail, distinct_labels FROM ("
                    + " SELECT session_id, place_label, place_detail,"
                    + " COUNT(DISTINCT place_label) OVER (PARTITION BY session_id)"
                    + "   AS distinct_labels,"
                    + " ROW_NUMBER() OVER (PARTITION BY session_id"
                    + "   ORDER BY segment_index, COALESCE(window_index, -1))"
                    + "   AS rank_in_session"
                    + " FROM " + READING_SOURCE
                    + " WHERE place_label IS NOT NULL) ordered_places"
                    + " WHERE rank_in_session = 1) p";

    /** The three segment counters every session line shows, as correlated counts. */
    private static final String SEGMENT_COUNTS = """
            (SELECT COUNT(*) FROM capture_segments g
              WHERE g.session_id = s.session_id) AS segment_total,
            (SELECT COUNT(*) FROM capture_segments g
              WHERE g.session_id = s.session_id AND g.state = 'ready') AS segment_ready,
            (SELECT COUNT(*) FROM capture_segments g
              WHERE g.session_id = s.session_id
                AND g.measurement_state IS NOT NULL) AS segment_measured
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCaptureSessionStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public CaptureSessionDocument createSession(CaptureSessionDocument session) {
        jdbcTemplate.update("""
                INSERT INTO capture_sessions (
                    session_id, device_id, state, started_at, created_at, updated_at, expires_at,
                    rodovia, sentido
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                session.sessionId(),
                session.deviceId(),
                session.state(),
                timestamp(session.startedAt()),
                timestamp(session.createdAt()),
                timestamp(session.updatedAt()),
                timestamp(session.expiresAt()),
                session.rodovia(),
                session.sentido() == null ? null : session.sentido().wireValue());
        return session;
    }

    @Override
    public CaptureSessionDocument getSession(UUID sessionId) {
        return findSession(sessionId)
                .orElseThrow(() -> new ApplicationException(
                        FailureKind.NOT_FOUND, "capture_session_not_found", "capture session does not exist"));
    }

    @Override
    public Optional<CaptureSessionDocument> findSession(UUID sessionId) {
        return jdbcTemplate.query(
                        "SELECT * FROM capture_sessions WHERE session_id = ?",
                        JdbcCaptureSessionStoreAdapter::mapSession,
                        sessionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<CaptureSegmentDocument> findSegment(UUID sessionId, int segmentIndex) {
        return jdbcTemplate.query(
                        SEGMENT_SELECT + " WHERE g.session_id = ? AND g.segment_index = ?",
                        JdbcCaptureSessionStoreAdapter::mapSegment,
                        sessionId,
                        segmentIndex)
                .stream()
                .findFirst();
    }

    @Override
    public CaptureSegmentDocument getSegment(UUID sessionId, int segmentIndex) {
        return findSegment(sessionId, segmentIndex).orElseThrow(() -> new ApplicationException(
                FailureKind.NOT_FOUND, "capture_segment_not_found", "capture segment does not exist"));
    }

    @Transactional
    @Override
    public CaptureSegmentDocument ensureSegment(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            Instant now) {
        getSession(sessionId);
        Optional<CaptureSegmentDocument> existing = findSegment(sessionId, segmentIndex);
        if (existing.isPresent()) {
            requireSameIdentity(existing.get(), idempotencyKey, capturedAt, durationMillis);
            return existing.get();
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO capture_segments (
                        session_id, segment_index, state, idempotency_key, captured_at,
                        duration_millis, created_at, updated_at
                    ) VALUES (?, ?, 'awaiting_upload', ?, ?, ?, ?, ?)
                    """,
                    sessionId,
                    segmentIndex,
                    idempotencyKey,
                    timestamp(capturedAt),
                    durationMillis,
                    timestamp(now),
                    timestamp(now));
        } catch (DuplicateKeyException ignored) {
            CaptureSegmentDocument concurrent = getSegment(sessionId, segmentIndex);
            requireSameIdentity(concurrent, idempotencyKey, capturedAt, durationMillis);
            return concurrent;
        }
        return getSegment(sessionId, segmentIndex);
    }

    @Override
    public CaptureSegmentDocument recordVideo(
            UUID sessionId,
            int segmentIndex,
            String objectKey,
            String sha256,
            long bytes,
            Instant now) {
        jdbcTemplate.update("""
                UPDATE capture_segments
                SET video_object_key = ?, video_sha256 = ?, video_bytes = ?, state = 'uploading', updated_at = ?
                WHERE session_id = ? AND segment_index = ?
                """, objectKey, sha256, bytes, timestamp(now), sessionId, segmentIndex);
        return getSegment(sessionId, segmentIndex);
    }

    @Override
    public CaptureSegmentDocument recordTelemetry(
            UUID sessionId,
            int segmentIndex,
            String objectKey,
            String sha256,
            long bytes,
            Instant now) {
        jdbcTemplate.update("""
                UPDATE capture_segments
                SET telemetry_object_key = ?, telemetry_sha256 = ?, telemetry_bytes = ?, state = 'uploading', updated_at = ?
                WHERE session_id = ? AND segment_index = ?
                """, objectKey, sha256, bytes, timestamp(now), sessionId, segmentIndex);
        return getSegment(sessionId, segmentIndex);
    }

    @Override
    public CaptureSegmentDocument markQueued(UUID sessionId, int segmentIndex, Instant now) {
        jdbcTemplate.update("""
                UPDATE capture_segments SET state = 'queued', updated_at = ?, error_code = NULL, error_message = NULL
                WHERE session_id = ? AND segment_index = ?
                """, timestamp(now), sessionId, segmentIndex);
        return getSegment(sessionId, segmentIndex);
    }

    @Override
    public CaptureSegmentDocument markQueueFailed(
            UUID sessionId,
            int segmentIndex,
            String errorCode,
            String errorMessage,
            Instant now) {
        jdbcTemplate.update("""
                UPDATE capture_segments
                SET state = 'failed', error_code = ?, error_message = ?, updated_at = ?
                WHERE session_id = ? AND segment_index = ?
                """,
                errorCode,
                errorMessage,
                timestamp(now),
                sessionId,
                segmentIndex);
        return getSegment(sessionId, segmentIndex);
    }

    @Override
    public CaptureSegmentDocument recordMeasurement(
            UUID sessionId,
            int segmentIndex,
            String objectKey,
            String runId,
            boolean mock,
            Instant measuredAt,
            MeasurementProjection projection,
            Instant now) {
        jdbcTemplate.update("""
                UPDATE capture_segments
                SET measurement_state = 'measured', measurement_object_key = ?, measurement_run_id = ?,
                    measurement_is_mock = ?, measured_at = ?, updated_at = ?,
                    measurement_extent95_p95_m = ?, measurement_extent95_max_m = ?,
                    measurement_level = ?, measurement_cells_measured = ?,
                    measurement_cells_abstained = ?, measurement_coverage = ?,
                    track_center_lat = ?, track_center_lon = ?, track_geojson = ?,
                    measurement_track_length_m = ?, track_location_quality = ?
                WHERE session_id = ? AND segment_index = ?
                """,
                objectKey,
                runId,
                mock,
                timestamp(measuredAt),
                timestamp(now),
                projection.extent95P95M(),
                projection.extent95MaxM(),
                projection.level(),
                projection.cellsMeasured(),
                projection.cellsAbstained(),
                projection.coverage(),
                projection.trackCenterLat(),
                projection.trackCenterLon(),
                projection.trackGeoJson(),
                projection.trackLengthM(),
                projection.trackLocationQuality(),
                sessionId,
                segmentIndex);
        return getSegment(sessionId, segmentIndex);
    }

    /**
     * Records one window's reading, then re-derives the segment's own summary from every window it
     * now has.
     *
     * <p>Written as update-then-insert rather than as an upsert because {@code ON CONFLICT ... DO
     * UPDATE} is not portable between PostgreSQL and the H2 the test suite runs on, and the pair
     * is exact inside the transaction that wraps them.
     *
     * <p>The rollup is not a convenience. The session screen, the per-segment counters and the
     * level chips all read {@code capture_segments}, and they were written when a segment was the
     * unit of measurement. Rather than teach each of them about windows, the segment keeps
     * reporting the worst window it has — the tallest grass in it, which is what decides whether a
     * crew goes — with the cell counts summed and the latest measurement time.
     */
    @Transactional
    @Override
    public CaptureSegmentDocument recordWindowMeasurement(
            UUID sessionId,
            int segmentIndex,
            int windowIndex,
            Double startMeters,
            Double endMeters,
            String objectKey,
            String runId,
            boolean mock,
            Instant measuredAt,
            MeasurementProjection projection,
            Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE capture_segment_windows
                SET start_meters = ?, end_meters = ?, measurement_state = 'measured',
                    measurement_object_key = ?, measurement_run_id = ?, measurement_is_mock = ?,
                    measured_at = ?, updated_at = ?,
                    measurement_extent95_p95_m = ?, measurement_extent95_max_m = ?,
                    measurement_level = ?, measurement_cells_measured = ?,
                    measurement_cells_abstained = ?, measurement_coverage = ?,
                    track_center_lat = ?, track_center_lon = ?, track_geojson = ?,
                    measurement_track_length_m = ?, track_location_quality = ?
                WHERE session_id = ? AND segment_index = ? AND window_index = ?
                """,
                startMeters,
                endMeters,
                objectKey,
                runId,
                mock,
                timestamp(measuredAt),
                timestamp(now),
                projection.extent95P95M(),
                projection.extent95MaxM(),
                projection.level(),
                projection.cellsMeasured(),
                projection.cellsAbstained(),
                projection.coverage(),
                projection.trackCenterLat(),
                projection.trackCenterLon(),
                projection.trackGeoJson(),
                projection.trackLengthM(),
                projection.trackLocationQuality(),
                sessionId,
                segmentIndex,
                windowIndex);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO capture_segment_windows (
                        session_id, segment_index, window_index, start_meters, end_meters,
                        measurement_state, measurement_object_key, measurement_run_id,
                        measurement_is_mock, measured_at, measurement_extent95_p95_m,
                        measurement_extent95_max_m, measurement_level, measurement_cells_measured,
                        measurement_cells_abstained, measurement_coverage,
                        measurement_track_length_m, track_center_lat, track_center_lon,
                        track_geojson, track_location_quality, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'measured', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    sessionId,
                    segmentIndex,
                    windowIndex,
                    startMeters,
                    endMeters,
                    objectKey,
                    runId,
                    mock,
                    timestamp(measuredAt),
                    projection.extent95P95M(),
                    projection.extent95MaxM(),
                    projection.level(),
                    projection.cellsMeasured(),
                    projection.cellsAbstained(),
                    projection.coverage(),
                    projection.trackLengthM(),
                    projection.trackCenterLat(),
                    projection.trackCenterLon(),
                    projection.trackGeoJson(),
                    projection.trackLocationQuality(),
                    timestamp(now),
                    timestamp(now));
        }
        rollUpWindows(sessionId, segmentIndex, now);
        return findWindow(sessionId, segmentIndex, windowIndex).orElseThrow(() -> new ApplicationException(
                FailureKind.NOT_FOUND, "capture_segment_window_not_found", "capture segment window does not exist"));
    }

    /**
     * Re-derives a segment's projection from its windows: the worst of them, and the sums.
     *
     * <p>The worst window is the one with the tallest grass, which is the reading that decides
     * whether a crew is sent. Its max and its level travel with it rather than being maxed
     * separately, so the three numbers on the row describe one reading and not three.
     *
     * <p>{@code measurement_object_key} and {@code measurement_run_id} are deliberately left
     * alone. There is no packet for a segment cut into windows — each window has its own — and
     * writing a key that resolves to nothing would put a link on the screen that answers 409.
     */
    private void rollUpWindows(UUID sessionId, int segmentIndex, Instant now) {
        Map<String, Object> worst = jdbcTemplate.queryForList("""
                SELECT measurement_extent95_p95_m, measurement_extent95_max_m, measurement_level
                  FROM capture_segment_windows
                 WHERE session_id = ? AND segment_index = ? AND measurement_state IS NOT NULL
                 ORDER BY measurement_extent95_p95_m DESC NULLS LAST, window_index
                 LIMIT 1
                """, sessionId, segmentIndex).stream().findFirst().orElse(Map.of());
        Map<String, Object> totals = jdbcTemplate.queryForList("""
                SELECT MAX(measured_at) AS measured_at,
                       SUM(measurement_cells_measured) AS cells_measured,
                       SUM(measurement_cells_abstained) AS cells_abstained
                  FROM capture_segment_windows
                 WHERE session_id = ? AND segment_index = ? AND measurement_state IS NOT NULL
                """, sessionId, segmentIndex).stream().findFirst().orElse(Map.of());

        jdbcTemplate.update("""
                UPDATE capture_segments
                SET measurement_state = 'measured', measured_at = ?, updated_at = ?,
                    measurement_extent95_p95_m = ?, measurement_extent95_max_m = ?,
                    measurement_level = ?, measurement_cells_measured = ?,
                    measurement_cells_abstained = ?
                WHERE session_id = ? AND segment_index = ?
                """,
                totals.get("measured_at"),
                timestamp(now),
                number(worst.get("measurement_extent95_p95_m"), Double.class),
                number(worst.get("measurement_extent95_max_m"), Double.class),
                number(worst.get("measurement_level"), Integer.class),
                number(totals.get("cells_measured"), Integer.class),
                number(totals.get("cells_abstained"), Integer.class),
                sessionId,
                segmentIndex);
    }

    /**
     * A number out of a generic row, in the type the column is read back as.
     *
     * <p>{@code SUM} widens to {@code BIGINT} in PostgreSQL and to {@code DECIMAL} in H2, and
     * neither fits an {@code INTEGER} parameter without being narrowed first.
     */
    private static <T extends Number> T number(Object value, Class<T> type) {
        if (!(value instanceof Number number)) {
            return null;
        }
        return type.cast(type == Integer.class ? (Number) number.intValue() : (Number) number.doubleValue());
    }

    @Override
    public CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt, Instant now) {
        jdbcTemplate.update("""
                UPDATE capture_sessions
                SET state = 'processing', last_segment_index = ?, ended_at = ?, updated_at = ?
                WHERE session_id = ? AND state IN ('recording', 'processing')
                """, lastSegmentIndex, timestamp(endedAt), timestamp(now), sessionId);
        Long ready = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments WHERE session_id = ? AND state = 'ready'",
                Long.class,
                sessionId);
        if (ready != null && ready == (long) lastSegmentIndex + 1) {
            jdbcTemplate.update(
                    "UPDATE capture_sessions SET state = 'ready', updated_at = ? WHERE session_id = ?",
                    timestamp(now),
                    sessionId);
        }
        return getSession(sessionId);
    }

    /**
     * One page of sessions, filtered, counted and ordered in SQL.
     *
     * <p>The clauses are assembled rather than written out because six optional filters are
     * sixty-four queries. The ordering ends in {@code session_id} so a page boundary cannot fall
     * between two sessions started in the same millisecond and show one of them twice.
     *
     * <p>The day and the order used to be applied by the browser, over the fifty rows it had
     * asked for. That is an answer about the page rather than about the data — the same defect
     * the readings list was moved into SQL to remove — and it is worse here, because a filter
     * that removes rows from one page of fifty leaves a screen that looks empty rather than
     * paged.
     *
     * <p>The select list is wrapped in a derived table so {@link CaptureSessionSort} can name
     * plain columns: the level counters exist only as output aliases, and an ORDER BY over the
     * join would have to know which side of it each name came from.
     */
    @Override
    public Page<CaptureSessionSummary> findSessions(CaptureSessionQuery query) {
        List<String> clauses = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        if (query.state() != null) {
            clauses.add("s.state = ?");
            arguments.add(query.state());
        }
        if (query.rodovia() != null) {
            clauses.add("s.rodovia = ?");
            arguments.add(query.rodovia());
        }
        if (query.sentido() != null) {
            clauses.add("s.sentido = ?");
            arguments.add(query.sentido().name());
        }
        if (query.measuredOnly()) {
            clauses.add("""
                    EXISTS (SELECT 1 FROM capture_segments g
                             WHERE g.session_id = s.session_id AND g.measurement_state IS NOT NULL)
                    """);
        }
        // Half-open, like the readings filter: a session started exactly at midnight belongs to
        // the day that begins there and not to both of the days that touch it.
        if (query.capturedFrom() != null) {
            clauses.add("s.started_at >= ?");
            arguments.add(Timestamp.from(query.capturedFrom()));
        }
        if (query.capturedTo() != null) {
            clauses.add("s.started_at < ?");
            arguments.add(Timestamp.from(query.capturedTo()));
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_sessions s" + where, Long.class, arguments.toArray());

        List<Object> paged = new ArrayList<>(arguments);
        paged.add(query.limit());
        paged.add(query.offset());
        // Every counter comes back with the row. One query per session would be a round trip per
        // line of the list, and the list is the first thing a dashboard draws.
        String source = "(SELECT s.*, "
                + SEGMENT_COUNTS
                + ", COALESCE(r.level_1, 0) AS reading_level_1"
                + ", COALESCE(r.level_2, 0) AS reading_level_2"
                + ", COALESCE(r.level_3, 0) AS reading_level_3"
                + ", COALESCE(r.level_0, 0) AS reading_unrated"
                + ", p.place_label AS reading_place_label"
                + ", p.place_detail AS reading_place_detail"
                + ", COALESCE(p.distinct_labels, 0) AS reading_place_labels"
                + " FROM capture_sessions s"
                // Left, not inner: a session recorded this morning has no reading yet and still
                // has to appear, at zero.
                + " LEFT JOIN " + SESSION_READING_COUNTS + " ON r.session_id = s.session_id"
                + " LEFT JOIN " + SESSION_PLACES + " ON p.session_id = s.session_id"
                + where
                + ") session_row";
        List<CaptureSessionSummary> items = jdbcTemplate.query(
                "SELECT * FROM " + source
                        + " ORDER BY " + query.sort().orderBy() + " LIMIT ? OFFSET ?",
                (result, row) -> new CaptureSessionSummary(
                        mapSession(result, row),
                        result.getLong("segment_total"),
                        result.getLong("segment_ready"),
                        result.getLong("segment_measured"),
                        new SessionReadingCounts(
                                result.getLong("reading_level_1"),
                                result.getLong("reading_level_2"),
                                result.getLong("reading_level_3"),
                                result.getLong("reading_unrated")),
                        new SessionPlace(
                                result.getString("reading_place_label"),
                                result.getString("reading_place_detail"),
                                result.getLong("reading_place_labels"))),
                paged.toArray());
        return new Page<>(items, total == null ? 0 : total, query.limit(), query.offset());
    }

    /**
     * Every segment of one session, in capture order.
     *
     * <p>Unbounded, and that is a decision for its callers rather than a claim that the list is
     * short: an hour of capture is three hundred and sixty segments. The route a dashboard reads
     * takes the paged overload below.
     */
    @Override
    public List<CaptureSegmentDocument> findSegments(UUID sessionId) {
        return jdbcTemplate.query(
                SEGMENT_SELECT + " WHERE g.session_id = ? ORDER BY g.segment_index",
                JdbcCaptureSessionStoreAdapter::mapSegment,
                sessionId);
    }

    /** One page of them. Ordered by index because a session is a sequence, not a ranking. */
    @Override
    public Page<CaptureSegmentDocument> findSegments(SegmentQuery query) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(query.sessionId());
        String where = " WHERE session_id = ?";
        if (query.level() != null) {
            where += query.level() == 0
                    ? " AND (measurement_level IS NULL OR measurement_level NOT IN (1, 2, 3))"
                    : " AND measurement_level = ?";
            if (query.level() != 0) {
                arguments.add(query.level());
            }
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments" + where, Long.class, arguments.toArray());

        List<Object> paged = new ArrayList<>(arguments);
        paged.add(query.limit());
        paged.add(query.offset());
        List<CaptureSegmentDocument> items = jdbcTemplate.query(
                SEGMENT_SELECT + where + " ORDER BY segment_index LIMIT ? OFFSET ?",
                JdbcCaptureSessionStoreAdapter::mapSegment,
                paged.toArray());
        return new Page<>(items, total == null ? 0 : total, query.limit(), query.offset());
    }

    /**
     * The level tally for one session, in one pass.
     *
     * <p>The chips above a paged list have to count the session and not the page, for the same
     * reason the readings list needed its own summary: a count of what is on screen is a fact
     * about the request.
     */
    @Override
    public MeasurementSummary summariseSegments(UUID sessionId) {
        return jdbcTemplate.query(
                """
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN measurement_level = 1 THEN 1 ELSE 0 END) AS level_1,
                       SUM(CASE WHEN measurement_level = 2 THEN 1 ELSE 0 END) AS level_2,
                       SUM(CASE WHEN measurement_level = 3 THEN 1 ELSE 0 END) AS level_3,
                       SUM(CASE WHEN measurement_level IN (1, 2, 3) THEN 0 ELSE 1 END) AS level_0,
                       MAX(measurement_extent95_p95_m) AS tallest,
                       SUM(CASE WHEN track_location_quality = 'good' THEN 1 ELSE 0 END) AS gps_good,
                       SUM(CASE WHEN track_location_quality = 'degraded' THEN 1 ELSE 0 END)
                           AS gps_degraded,
                       SUM(CASE WHEN track_location_quality IN ('good', 'degraded') THEN 0 ELSE 1 END)
                           AS gps_unavailable
                  FROM capture_segments
                 WHERE session_id = ?
                """,
                result -> {
                    if (!result.next()) {
                        return MeasurementSummary.empty();
                    }
                    double tallest = result.getDouble("tallest");
                    return new MeasurementSummary(
                            result.getLong("total"),
                            Map.of(
                                    0, result.getLong("level_0"),
                                    1, result.getLong("level_1"),
                                    2, result.getLong("level_2"),
                                    3, result.getLong("level_3")),
                            result.wasNull() ? null : tallest,
                            Map.of(
                                    "good", result.getLong("gps_good"),
                                    "degraded", result.getLong("gps_degraded"),
                                    "unavailable", result.getLong("gps_unavailable")));
                },
                sessionId);
    }

    /**
     * One page of readings, ordered and filtered in SQL.
     *
     * <p>This used to hand out two hundred rows in whatever order they were measured and let the
     * browser sort them. That is only the right answer while everything fits in one request: past
     * that, the first page is the tallest of an arbitrary two hundred instead of the tallest there
     * is, and nothing on screen admits it. V13 adds the two indexes these orders read, and V15 the
     * matching ones on the window table.
     *
     * <p>A row is a window now, not a segment. See {@link #READING_SOURCE} for what that set is.
     */
    @Override
    public Page<CaptureSegmentDocument> findMeasurements(MeasurementQuery query) {
        List<Object> arguments = new ArrayList<>();
        String where = whereFor(query, arguments);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + READING_SOURCE + where, Long.class, arguments.toArray());

        List<Object> paged = new ArrayList<>(arguments);
        paged.add(query.limit());
        paged.add(query.offset());
        List<CaptureSegmentDocument> items = jdbcTemplate.query(
                "SELECT * FROM " + READING_SOURCE + where
                        + " ORDER BY " + query.sort().orderBy() + " LIMIT ? OFFSET ?",
                JdbcCaptureSessionStoreAdapter::mapSegment,
                paged.toArray());
        return new Page<>(items, total == null ? 0 : total, query.limit(), query.offset());
    }

    /**
     * One window of one segment.
     *
     * <p>Absent means it was never measured. There is no row for a window nobody measured, because
     * nothing outside the extractor knows how many windows a segment was cut into until the
     * measurements arrive.
     */
    @Override
    public Optional<CaptureSegmentDocument> findWindow(UUID sessionId, int segmentIndex, int windowIndex) {
        return jdbcTemplate.query(
                        WINDOW_SELECT
                                + " WHERE w.session_id = ? AND w.segment_index = ? AND w.window_index = ?",
                        JdbcCaptureSessionStoreAdapter::mapSegment,
                        sessionId,
                        segmentIndex,
                        windowIndex)
                .stream()
                .findFirst();
    }

    /** A segment's windows, in the order the extractor cut them. */
    @Override
    public List<CaptureSegmentDocument> findWindows(UUID sessionId, int segmentIndex) {
        return jdbcTemplate.query(
                WINDOW_SELECT + " WHERE w.session_id = ? AND w.segment_index = ? ORDER BY w.window_index",
                JdbcCaptureSessionStoreAdapter::mapSegment,
                sessionId,
                segmentIndex);
    }

    /** Every window of a session, for the drawing of the route. */
    @Override
    public List<CaptureSegmentDocument> findWindows(UUID sessionId) {
        return jdbcTemplate.query(
                WINDOW_SELECT + " WHERE w.session_id = ? ORDER BY w.segment_index, w.window_index",
                JdbcCaptureSessionStoreAdapter::mapSegment,
                sessionId);
    }

    /**
     * The counters, in one pass over the same filter.
     *
     * <p>Four separate counts would be four scans of the same rows to draw four cards. The level
     * is bucketed in SQL with the same rule the rest of the system uses: anything that is not 1, 2
     * or 3 — null included — is level 0, which is "not classified" and not "low".
     */
    @Override
    public MeasurementSummary summariseMeasurements(MeasurementQuery query) {
        List<Object> arguments = new ArrayList<>();
        String where = whereFor(query.withoutLevel(), arguments);

        return jdbcTemplate.query(
                """
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN measurement_level = 1 THEN 1 ELSE 0 END) AS level_1,
                       SUM(CASE WHEN measurement_level = 2 THEN 1 ELSE 0 END) AS level_2,
                       SUM(CASE WHEN measurement_level = 3 THEN 1 ELSE 0 END) AS level_3,
                       SUM(CASE WHEN measurement_level IN (1, 2, 3) THEN 0 ELSE 1 END) AS level_0,
                       MAX(measurement_extent95_p95_m) AS tallest,
                       SUM(CASE WHEN track_location_quality = 'good' THEN 1 ELSE 0 END) AS gps_good,
                       SUM(CASE WHEN track_location_quality = 'degraded' THEN 1 ELSE 0 END)
                           AS gps_degraded,
                       SUM(CASE WHEN track_location_quality IN ('good', 'degraded') THEN 0 ELSE 1 END)
                           AS gps_unavailable
                  FROM """
                        + READING_SOURCE
                        + where,
                result -> {
                    if (!result.next()) {
                        return MeasurementSummary.empty();
                    }
                    double tallest = result.getDouble("tallest");
                    return new MeasurementSummary(
                            result.getLong("total"),
                            Map.of(
                                    0, result.getLong("level_0"),
                                    1, result.getLong("level_1"),
                                    2, result.getLong("level_2"),
                                    3, result.getLong("level_3")),
                            result.wasNull() ? null : tallest,
                            Map.of(
                                    "good", result.getLong("gps_good"),
                                    "degraded", result.getLong("gps_degraded"),
                                    "unavailable", result.getLong("gps_unavailable")));
                },
                arguments.toArray());
    }

    /**
     * The filter both of the above share, so a count can never disagree with the page it labels.
     *
     * <p>The capture window is half-open. Two adjacent days sharing the row recorded exactly at
     * midnight would put it on both screens and in neither total.
     */
    private static String whereFor(MeasurementQuery query, List<Object> arguments) {
        List<String> clauses = new ArrayList<>();
        clauses.add("measurement_state IS NOT NULL");

        if (query.level() != null) {
            if (query.level() == 0) {
                clauses.add("(measurement_level IS NULL OR measurement_level NOT IN (1, 2, 3))");
            } else {
                clauses.add("measurement_level = ?");
                arguments.add(query.level());
            }
        }
        if (query.capturedFrom() != null) {
            clauses.add("captured_at >= ?");
            arguments.add(Timestamp.from(query.capturedFrom()));
        }
        if (query.capturedTo() != null) {
            clauses.add("captured_at < ?");
            arguments.add(Timestamp.from(query.capturedTo()));
        }
        if (query.search() != null) {
            // The same three fields the dashboard composes its label from, so what a person types
            // is matched against what they read on the row. Both sides are folded: the term by
            // MeasurementQuery, the column here, so "paraiso" finds Rua do Paraíso.
            clauses.add("""
                    (%s LIKE ? OR %s LIKE ? OR %s LIKE ?)
                    """
                    .formatted(folded("place_label"), folded("place_detail"), folded("place_road")));
            String pattern = "%" + query.search().toLowerCase(Locale.ROOT) + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        return " WHERE " + String.join(" AND ", clauses);
    }

    /**
     * A place column lowered and stripped of accents, in a form both databases accept.
     *
     * <p>PostgreSQL has {@code unaccent}, but it is an extension and the test database is H2.
     * TRANSLATE is in both, and the alphabet below is the one Brazilian place names use. The two
     * strings must stay the same length, which is what makes this a constant and not a loop.
     *
     * <p>The column name is written here in source, never taken from a caller. The term itself
     * goes in as a parameter.
     */
    private static String folded(String column) {
        return "TRANSLATE(LOWER(COALESCE(%s, '')), '%s', '%s')".formatted(column, ACCENTED, PLAIN);
    }

    private static final String ACCENTED = "áàâãäéèêëíìîïóòôõöúùûüçñ";
    private static final String PLAIN = "aaaaaeeeeiiiiooooouuuucn";

    @Override
    public long segmentCount(UUID sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments WHERE session_id = ?", Long.class, sessionId);
        return value == null ? 0 : value;
    }

    @Override
    public long measuredSegmentCount(UUID sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments WHERE session_id = ? AND measurement_state IS NOT NULL",
                Long.class,
                sessionId);
        return value == null ? 0 : value;
    }

    @Override
    public long readySegmentCount(UUID sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments WHERE session_id = ? AND state = 'ready'",
                Long.class,
                sessionId);
        return value == null ? 0 : value;
    }

    /**
     * The same tally the listing carries, for one session.
     *
     * <p>Over the same source, so the session a reader opened from the list reports what the list
     * said it would. The level rule is the one the rest of the system uses: anything that is not
     * 1, 2 or 3 — null included — is unrated, which is "not classified" and not "low".
     */
    @Override
    public SessionReadingCounts readingCounts(UUID sessionId) {
        return jdbcTemplate.query(
                """
                SELECT SUM(CASE WHEN measurement_level = 1 THEN 1 ELSE 0 END) AS level_1,
                       SUM(CASE WHEN measurement_level = 2 THEN 1 ELSE 0 END) AS level_2,
                       SUM(CASE WHEN measurement_level = 3 THEN 1 ELSE 0 END) AS level_3,
                       SUM(CASE WHEN measurement_level IN (1, 2, 3) THEN 0 ELSE 1 END) AS level_0
                  FROM """
                        + READING_SOURCE
                        + " WHERE session_id = ?",
                result -> {
                    if (!result.next()) {
                        return SessionReadingCounts.EMPTY;
                    }
                    return new SessionReadingCounts(
                            result.getLong("level_1"),
                            result.getLong("level_2"),
                            result.getLong("level_3"),
                            result.getLong("level_0"));
                },
                sessionId);
    }

    /**
     * Where one session was, by the rule its line in the list answers with.
     *
     * <p>The first geocoded reading in capture order, and how many streets the session
     * crossed altogether. A session whose readings carry no place answers nowhere, which a
     * screen says out loud rather than filling in from a guess.
     */
    @Override
    public SessionPlace readingPlace(UUID sessionId) {
        return jdbcTemplate.query(
                "SELECT place_label, place_detail,"
                        + " COUNT(DISTINCT place_label) OVER () AS distinct_labels"
                        + " FROM " + READING_SOURCE
                        + " WHERE session_id = ? AND place_label IS NOT NULL"
                        + " ORDER BY segment_index, COALESCE(window_index, -1)",
                result -> {
                    if (!result.next()) {
                        return SessionPlace.NOWHERE;
                    }
                    return new SessionPlace(
                            result.getString("place_label"),
                            result.getString("place_detail"),
                            result.getLong("distinct_labels"));
                },
                sessionId);
    }

    private static CaptureSessionDocument mapSession(ResultSet result, int row) throws SQLException {
        return new CaptureSessionDocument(
                result.getObject("session_id", UUID.class),
                result.getString("device_id"),
                result.getString("state"),
                instant(result, "started_at"),
                instant(result, "ended_at"),
                instant(result, "created_at"),
                instant(result, "updated_at"),
                instant(result, "expires_at"),
                result.getObject("last_segment_index", Integer.class),
                result.getString("rodovia"),
                Sentido.of(result.getString("sentido")));
    }

    @Override
    @Transactional
    public void replaceFrameReadings(UUID sessionId, int segmentIndex, List<FrameReadings> readings) {
        jdbcTemplate.update(
                "DELETE FROM segment_frame_readings WHERE session_id = ? AND segment_index = ?",
                sessionId,
                segmentIndex);
        insertFrameReadings(sessionId, segmentIndex, readings);
    }

    @Override
    @Transactional
    public void mergeFrameReadings(UUID sessionId, int segmentIndex, List<FrameReadings> readings) {
        for (FrameReadings reading : readings) {
            jdbcTemplate.update(
                    """
                    DELETE FROM segment_frame_readings
                     WHERE session_id = ? AND segment_index = ? AND canonical_frame = ?
                    """,
                    sessionId,
                    segmentIndex,
                    reading.canonicalFrame());
        }
        insertFrameReadings(sessionId, segmentIndex, readings);
    }

    private void insertFrameReadings(UUID sessionId, int segmentIndex, List<FrameReadings> readings) {
        Timestamp now = Timestamp.from(Instant.now());
        for (FrameReadings reading : readings) {
            jdbcTemplate.update(
                    """
                    INSERT INTO segment_frame_readings (session_id, segment_index,
                        canonical_frame, cells_voted, sample_count, extent95_median_m,
                        extent95_max_m, largest_disagreement_m, evidence_for_cells, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    sessionId,
                    segmentIndex,
                    reading.canonicalFrame(),
                    reading.cellsVoted(),
                    reading.sampleCount(),
                    reading.extent95MedianM(),
                    reading.extent95MaxM(),
                    reading.largestDisagreementM(),
                    reading.evidenceForCells(),
                    now);
        }
    }

    @Override
    public Optional<FrameReadings> findFrameReadings(
            UUID sessionId, int segmentIndex, int canonicalFrame) {
        return jdbcTemplate
                .query(
                        """
                        SELECT * FROM segment_frame_readings
                         WHERE session_id = ? AND segment_index = ? AND canonical_frame = ?
                        """,
                        JdbcCaptureSessionStoreAdapter::mapFrameReadings,
                        sessionId,
                        segmentIndex,
                        canonicalFrame)
                .stream()
                .findFirst();
    }

    @Override
    public List<CaptureSegmentDocument> findSegmentsAwaitingFrameReadings(int limit) {
        return jdbcTemplate.query(
                SEGMENT_SELECT
                        + """
                         WHERE g.measurement_state IS NOT NULL
                           AND NOT EXISTS (SELECT 1 FROM segment_frame_readings r
                                            WHERE r.session_id = g.session_id
                                              AND r.segment_index = g.segment_index)
                         ORDER BY g.measured_at DESC NULLS LAST, g.segment_index
                         LIMIT ?
                        """,
                JdbcCaptureSessionStoreAdapter::mapSegment,
                limit);
    }

    private static FrameReadings mapFrameReadings(ResultSet result, int row) throws SQLException {
        return new FrameReadings(
                result.getInt("canonical_frame"),
                result.getInt("cells_voted"),
                result.getLong("sample_count"),
                result.getObject("extent95_median_m", Double.class),
                result.getObject("extent95_max_m", Double.class),
                result.getObject("largest_disagreement_m", Double.class),
                result.getInt("evidence_for_cells"));
    }

    private static CaptureSegmentDocument mapSegment(ResultSet result, int row) throws SQLException {
        return new CaptureSegmentDocument(
                result.getObject("session_id", UUID.class),
                result.getInt("segment_index"),
                result.getString("state"),
                result.getString("idempotency_key"),
                instant(result, "captured_at"),
                result.getLong("duration_millis"),
                result.getString("video_object_key"),
                result.getString("video_sha256"),
                result.getObject("video_bytes", Long.class),
                result.getString("telemetry_object_key"),
                result.getString("telemetry_sha256"),
                result.getObject("telemetry_bytes", Long.class),
                result.getString("manifest_object_key"),
                result.getObject("frame_count", Integer.class),
                result.getString("error_code"),
                result.getString("error_message"),
                result.getString("measurement_state"),
                result.getString("measurement_object_key"),
                result.getString("measurement_run_id"),
                result.getObject("measurement_is_mock", Boolean.class),
                instant(result, "measured_at"),
                mapProjection(result),
                mapPlace(result),
                result.getObject("window_index", Integer.class),
                result.getObject("window_start_meters", Double.class),
                result.getObject("window_end_meters", Double.class),
                result.getInt("window_count"),
                result.getInt("measured_window_count"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    /**
     * Null until the resolver has been round, so a caller can tell "not asked yet" from
     * "asked and there is nothing there". The second reads as a row with a timestamp and no
     * label.
     */
    private static SegmentPlace mapPlace(ResultSet result) throws SQLException {
        Instant resolvedAt = instant(result, "place_resolved_at");
        if (resolvedAt == null) {
            return null;
        }
        return new SegmentPlace(
                result.getString("place_label"),
                result.getString("place_detail"),
                result.getString("place_house_number"),
                result.getString("place_road"),
                result.getObject("place_km", Integer.class),
                result.getObject("place_km_offset_m", Double.class),
                result.getString("place_source"),
                resolvedAt);
    }

    /**
     * Readings with a coordinate and no name yet, windows included.
     *
     * <p>Over the same union the list reads, so a window is asked about on its own rather than
     * inheriting whatever street its segment's middle happened to fall on.
     */
    @Override
    public List<CaptureSegmentDocument> findSegmentsAwaitingPlace(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM " + READING_SOURCE
                        + """
                         WHERE track_center_lat IS NOT NULL
                           AND place_resolved_at IS NULL
                         ORDER BY measured_at DESC NULLS LAST, segment_index, window_index
                         LIMIT ?
                        """,
                JdbcCaptureSessionStoreAdapter::mapSegment,
                limit);
    }

    @Override
    public void recordPlace(UUID sessionId, int segmentIndex, Integer windowIndex, SegmentPlace place) {
        String statement = windowIndex == null
                ? """
                  UPDATE capture_segments
                     SET place_label = ?, place_detail = ?, place_house_number = ?,
                         place_road = ?, place_km = ?, place_km_offset_m = ?,
                         place_source = ?, place_resolved_at = ?
                   WHERE session_id = ? AND segment_index = ?
                  """
                : """
                  UPDATE capture_segment_windows
                     SET place_label = ?, place_detail = ?, place_house_number = ?,
                         place_road = ?, place_km = ?, place_km_offset_m = ?,
                         place_source = ?, place_resolved_at = ?
                   WHERE session_id = ? AND segment_index = ? AND window_index = ?
                  """;
        // Added one at a time rather than with List.of, which refuses a null, and every one of
        // these columns is legitimately null when the lookup found nothing there.
        List<Object> arguments = new ArrayList<>();
        arguments.add(place.label());
        arguments.add(place.detail());
        arguments.add(place.houseNumber());
        arguments.add(place.road());
        arguments.add(place.km());
        arguments.add(place.kmOffsetMetres());
        arguments.add(place.source());
        arguments.add(Timestamp.from(place.resolvedAt()));
        arguments.add(sessionId);
        arguments.add(segmentIndex);
        if (windowIndex != null) {
            arguments.add(windowIndex);
        }
        jdbcTemplate.update(statement, arguments.toArray());
    }

    private static MeasurementProjection mapProjection(ResultSet result) throws SQLException {
        return new MeasurementProjection(
                result.getObject("measurement_extent95_p95_m", Double.class),
                result.getObject("measurement_extent95_max_m", Double.class),
                result.getObject("measurement_level", Integer.class),
                result.getObject("measurement_cells_measured", Integer.class),
                result.getObject("measurement_cells_abstained", Integer.class),
                result.getObject("measurement_coverage", Double.class),
                result.getObject("track_center_lat", Double.class),
                result.getObject("track_center_lon", Double.class),
                result.getString("track_geojson"),
                result.getObject("measurement_track_length_m", Double.class),
                result.getString("track_location_quality"));
    }

    private static void requireSameIdentity(
            CaptureSegmentDocument existing,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis) {
        if (!existing.idempotencyKey().equals(idempotencyKey)
                || !existing.capturedAt().equals(capturedAt)
                || existing.durationMillis() != durationMillis) {
            throw new ApplicationException(
                    FailureKind.CONFLICT,
                    "segment_identity_conflict",
                    "segment index already belongs to different capture metadata");
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
