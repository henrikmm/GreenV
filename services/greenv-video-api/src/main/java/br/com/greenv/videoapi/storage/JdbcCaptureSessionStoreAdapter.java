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
import br.com.greenv.videoapi.domain.SegmentTravel;
import br.com.greenv.videoapi.domain.Sentido;
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
                        "SELECT * FROM capture_segments WHERE session_id = ? AND segment_index = ?",
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
     * One page of sessions, newest first.
     *
     * <p>The clauses are assembled rather than written out because five optional filters are
     * thirty-two queries. {@code session_id} joins the ordering so a page boundary cannot fall
     * between two sessions started in the same millisecond and show one of them twice.
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
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_sessions s" + where, Long.class, arguments.toArray());

        List<Object> paged = new ArrayList<>(arguments);
        paged.add(query.limit());
        paged.add(query.offset());
        // The three counters come back with the row. One query per session would be a round trip
        // per line of the list, and the list is the first thing a dashboard draws.
        List<CaptureSessionSummary> items = jdbcTemplate.query(
                """
                SELECT s.*,
                       (SELECT COUNT(*) FROM capture_segments g
                         WHERE g.session_id = s.session_id) AS segment_total,
                       (SELECT COUNT(*) FROM capture_segments g
                         WHERE g.session_id = s.session_id AND g.state = 'ready') AS segment_ready,
                       (SELECT COUNT(*) FROM capture_segments g
                         WHERE g.session_id = s.session_id
                           AND g.measurement_state IS NOT NULL) AS segment_measured
                  FROM capture_sessions s
                """
                        + where
                        + " ORDER BY s.started_at DESC, s.session_id DESC LIMIT ? OFFSET ?",
                (result, row) -> new CaptureSessionSummary(
                        mapSession(result, row),
                        result.getLong("segment_total"),
                        result.getLong("segment_ready"),
                        result.getLong("segment_measured")),
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
                "SELECT * FROM capture_segments WHERE session_id = ? ORDER BY segment_index",
                JdbcCaptureSessionStoreAdapter::mapSegment,
                sessionId);
    }

    @Transactional
    @Override
    public void recordTrackLengths(UUID sessionId, Map<Integer, Double> metresBySegment) {
        for (Map.Entry<Integer, Double> entry : metresBySegment.entrySet()) {
            jdbcTemplate.update(
                    "UPDATE capture_segments SET measurement_track_length_m = ?"
                            + " WHERE session_id = ? AND segment_index = ?",
                    entry.getValue(),
                    sessionId,
                    entry.getKey());
        }
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
                "SELECT * FROM capture_segments" + where + " ORDER BY segment_index LIMIT ? OFFSET ?",
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
                       MAX(measurement_extent95_p95_m) AS tallest
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
                            result.wasNull() ? null : tallest);
                },
                sessionId);
    }

    /**
     * One page of readings, ordered and filtered in SQL.
     *
     * <p>This used to hand out two hundred rows in whatever order they were measured and let the
     * browser sort them. That is only the right answer while everything fits in one request: past
     * that, the first page is the tallest of an arbitrary two hundred instead of the tallest there
     * is, and nothing on screen admits it. V13 adds the two indexes these orders read.
     */
    @Override
    public Page<CaptureSegmentDocument> findMeasurements(MeasurementQuery query) {
        List<Object> arguments = new ArrayList<>();
        String where = whereFor(query, arguments);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments" + where, Long.class, arguments.toArray());

        List<Object> paged = new ArrayList<>(arguments);
        paged.add(query.limit());
        paged.add(query.offset());
        List<CaptureSegmentDocument> items = jdbcTemplate.query(
                "SELECT * FROM capture_segments" + where
                        + " ORDER BY " + query.sort().orderBy() + " LIMIT ? OFFSET ?",
                JdbcCaptureSessionStoreAdapter::mapSegment,
                paged.toArray());
        return new Page<>(items, total == null ? 0 : total, query.limit(), query.offset());
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
                       MAX(measurement_extent95_p95_m) AS tallest
                  FROM capture_segments
                """
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
                            result.wasNull() ? null : tallest);
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
                """
                SELECT g.* FROM capture_segments g
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

    @Override
    public List<CaptureSegmentDocument> findSegmentsAwaitingPlace(int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM capture_segments
                 WHERE track_center_lat IS NOT NULL
                   AND place_resolved_at IS NULL
                 ORDER BY measured_at DESC NULLS LAST, segment_index
                 LIMIT ?
                """,
                JdbcCaptureSessionStoreAdapter::mapSegment,
                limit);
    }

    @Override
    public void recordPlace(UUID sessionId, int segmentIndex, SegmentPlace place) {
        jdbcTemplate.update(
                """
                UPDATE capture_segments
                   SET place_label = ?, place_detail = ?, place_house_number = ?,
                       place_road = ?, place_km = ?, place_km_offset_m = ?,
                       place_source = ?, place_resolved_at = ?
                 WHERE session_id = ? AND segment_index = ?
                """,
                place.label(),
                place.detail(),
                place.houseNumber(),
                place.road(),
                place.km(),
                place.kmOffsetMetres(),
                place.source(),
                Timestamp.from(place.resolvedAt()),
                sessionId,
                segmentIndex);
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
