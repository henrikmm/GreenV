package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.AuthSessionDocument;
import br.com.greenv.videoapi.port.AuthSessionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAuthSessionStoreAdapter implements AuthSessionStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthSessionStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AuthSessionDocument insert(AuthSessionDocument session) {
        jdbcTemplate.update("""
                INSERT INTO auth_sessions (
                    session_id, user_id, refresh_token_hash, fingerprint_hash,
                    issued_at, expires_at, last_used_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                session.sessionId(),
                session.userId(),
                session.refreshTokenHash(),
                session.fingerprintHash(),
                timestamp(session.issuedAt()),
                timestamp(session.expiresAt()),
                timestamp(session.lastUsedAt()));
        return session;
    }

    @Override
    public Optional<AuthSessionDocument> findByRefreshTokenHash(String refreshTokenHash) {
        return jdbcTemplate.query(
                        "SELECT * FROM auth_sessions WHERE refresh_token_hash = ?",
                        JdbcAuthSessionStoreAdapter::mapSession,
                        refreshTokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AuthSessionDocument> findById(UUID sessionId) {
        return jdbcTemplate.query(
                        "SELECT * FROM auth_sessions WHERE session_id = ?",
                        JdbcAuthSessionStoreAdapter::mapSession,
                        sessionId)
                .stream()
                .findFirst();
    }

    @Override
    public void markRotated(UUID sessionId, UUID successorId, Instant when) {
        jdbcTemplate.update("""
                UPDATE auth_sessions
                   SET replaced_by = ?, revoked_at = ?, revoked_reason = ?, last_used_at = ?
                 WHERE session_id = ?
                """,
                successorId,
                timestamp(when),
                AuthSessionDocument.REVOKED_ROTATED,
                timestamp(when),
                sessionId);
    }

    @Override
    public void revoke(UUID sessionId, String reason, Instant when) {
        jdbcTemplate.update("""
                UPDATE auth_sessions
                   SET revoked_at = ?, revoked_reason = ?
                 WHERE session_id = ? AND revoked_at IS NULL
                """,
                timestamp(when),
                reason,
                sessionId);
    }

    @Override
    public int revokeAllForUser(UUID userId, String reason, Instant when) {
        return jdbcTemplate.update("""
                UPDATE auth_sessions
                   SET revoked_at = ?, revoked_reason = ?
                 WHERE user_id = ? AND revoked_at IS NULL
                """,
                timestamp(when),
                reason,
                userId);
    }

    @Override
    public int deleteExpired(Instant before) {
        return jdbcTemplate.update("DELETE FROM auth_sessions WHERE expires_at < ?", timestamp(before));
    }

    private static AuthSessionDocument mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new AuthSessionDocument(
                rs.getObject("session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                // CHAR(64) pads on some engines; the digest comparison must not see the padding.
                trimmed(rs.getString("refresh_token_hash")),
                trimmed(rs.getString("fingerprint_hash")),
                instant(rs.getTimestamp("issued_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("revoked_at")),
                rs.getString("revoked_reason"),
                rs.getObject("replaced_by", UUID.class));
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
