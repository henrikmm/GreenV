package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.Sentido;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

    @Override
    public long segmentCount(UUID sessionId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments WHERE session_id = ?", Long.class, sessionId);
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
                instant(result, "created_at"),
                instant(result, "updated_at"));
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
