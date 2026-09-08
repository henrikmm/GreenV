package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.AuthRole;
import br.com.greenv.videoapi.domain.AuthUserDocument;
import br.com.greenv.videoapi.port.UserStore;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcUserStoreAdapter implements UserStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AuthUserDocument insert(AuthUserDocument user) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO auth_users (
                        user_id, email, email_normalized, password_hash, display_name,
                        role, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    user.userId(),
                    user.email(),
                    user.emailNormalized(),
                    user.passwordHash(),
                    user.displayName(),
                    user.role().name(),
                    user.status(),
                    timestamp(user.createdAt()),
                    timestamp(user.updatedAt()));
            return user;
        } catch (DuplicateKeyException e) {
            throw new ApplicationException(
                    FailureKind.CONFLICT, "user_already_exists", "a user with that email already exists");
        }
    }

    @Override
    public Optional<AuthUserDocument> findByNormalizedEmail(String emailNormalized) {
        return jdbcTemplate.query(
                        "SELECT * FROM auth_users WHERE email_normalized = ?",
                        JdbcUserStoreAdapter::mapUser,
                        emailNormalized)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AuthUserDocument> findById(UUID userId) {
        return jdbcTemplate.query(
                        "SELECT * FROM auth_users WHERE user_id = ?", JdbcUserStoreAdapter::mapUser, userId)
                .stream()
                .findFirst();
    }

    @Override
    public void recordLogin(UUID userId, Instant when) {
        jdbcTemplate.update(
                "UPDATE auth_users SET last_login_at = ?, updated_at = ? WHERE user_id = ?",
                timestamp(when),
                timestamp(when),
                userId);
    }

    private static AuthUserDocument mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new AuthUserDocument(
                rs.getObject("user_id", UUID.class),
                rs.getString("email"),
                rs.getString("email_normalized"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                AuthRole.of(rs.getString("role")),
                rs.getString("status"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("last_login_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
