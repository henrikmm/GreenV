package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.AuthClientDocument;
import br.com.greenv.videoapi.domain.AuthRole;
import br.com.greenv.videoapi.port.OAuthClientStore;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcOAuthClientStoreAdapter implements OAuthClientStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOAuthClientStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AuthClientDocument insert(AuthClientDocument client) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO auth_clients (
                        client_id, client_secret_hash, display_name, role, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    client.clientId(),
                    client.clientSecretHash(),
                    client.displayName(),
                    client.role().name(),
                    client.status(),
                    Timestamp.from(client.createdAt()),
                    Timestamp.from(client.updatedAt()));
            return client;
        } catch (DuplicateKeyException e) {
            throw new ApplicationException(
                    FailureKind.CONFLICT, "client_already_exists", "a client with that id already exists");
        }
    }

    @Override
    public Optional<AuthClientDocument> findById(String clientId) {
        return jdbcTemplate.query(
                        "SELECT * FROM auth_clients WHERE client_id = ?",
                        JdbcOAuthClientStoreAdapter::mapClient,
                        clientId)
                .stream()
                .findFirst();
    }

    @Override
    public void recordUse(String clientId, Instant when) {
        jdbcTemplate.update(
                "UPDATE auth_clients SET last_used_at = ? WHERE client_id = ?",
                Timestamp.from(when),
                clientId);
    }

    private static AuthClientDocument mapClient(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        return new AuthClientDocument(
                rs.getString("client_id"),
                rs.getString("client_secret_hash"),
                rs.getString("display_name"),
                AuthRole.of(rs.getString("role")),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                lastUsed == null ? null : lastUsed.toInstant());
    }
}
