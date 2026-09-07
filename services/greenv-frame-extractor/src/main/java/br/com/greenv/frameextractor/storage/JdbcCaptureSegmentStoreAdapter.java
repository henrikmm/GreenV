package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcCaptureSegmentStoreAdapter implements CaptureSegmentStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCaptureSegmentStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String state(SegmentExtractionRequest request) {
        return jdbcTemplate.query(
                        "SELECT state FROM capture_segments WHERE session_id = ? AND segment_index = ?",
                        (result, row) -> result.getString(1),
                        request.sessionId(),
                        request.segmentIndex())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ExtractionException(
                        "capture_segment_missing",
                        "queued capture segment does not exist in the database",
                        false));
    }

    @Override
    public void markValidating(SegmentExtractionRequest request, Instant now) {
        int changed = jdbcTemplate.update("""
                UPDATE capture_segments
                SET state = 'validating', updated_at = ?, error_code = NULL, error_message = NULL
                WHERE session_id = ? AND segment_index = ?
                  AND video_sha256 = ? AND telemetry_sha256 = ?
                """,
                timestamp(now),
                request.sessionId(),
                request.segmentIndex(),
                request.videoSha256(),
                request.telemetrySha256());
        if (changed != 1) {
            throw new ExtractionException(
                    "capture_segment_generation_mismatch",
                    "queued object checksums do not match the database row",
                    false);
        }
    }

    @Override
    public void markReady(
            SegmentExtractionRequest request,
            String manifestObjectKey,
            int frameCount,
            Instant now) {
        jdbcTemplate.update("""
                UPDATE capture_segments
                SET state = 'ready', manifest_object_key = ?, frame_count = ?, updated_at = ?,
                    error_code = NULL, error_message = NULL
                WHERE session_id = ? AND segment_index = ?
                """,
                manifestObjectKey,
                frameCount,
                timestamp(now),
                request.sessionId(),
                request.segmentIndex());
        refreshSession(request.sessionId(), now);
    }

    @Override
    public void markError(
            SegmentExtractionRequest request,
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
                request.sessionId(),
                request.segmentIndex());
    }

    private void refreshSession(java.util.UUID sessionId, Instant now) {
        Integer lastIndex = jdbcTemplate.query(
                        "SELECT last_segment_index FROM capture_sessions WHERE session_id = ?",
                        (result, row) -> result.getObject(1, Integer.class),
                        sessionId)
                .stream()
                .findFirst()
                .orElse(null);
        if (lastIndex == null) {
            return;
        }
        Long ready = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture_segments WHERE session_id = ? AND state = 'ready'",
                Long.class,
                sessionId);
        if (ready != null && ready == (long) lastIndex + 1) {
            jdbcTemplate.update(
                    "UPDATE capture_sessions SET state = 'ready', updated_at = ? WHERE session_id = ?",
                    timestamp(now),
                    sessionId);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
