package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.AuthSessionDocument;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistent login sessions, keyed for lookup by the SHA-256 of the refresh token. */
public interface AuthSessionStore {

    AuthSessionDocument insert(AuthSessionDocument session);

    Optional<AuthSessionDocument> findByRefreshTokenHash(String refreshTokenHash);

    Optional<AuthSessionDocument> findById(UUID sessionId);

    /** Marks {@code sessionId} as exchanged for {@code successorId}. */
    void markRotated(UUID sessionId, UUID successorId, Instant when);

    void revoke(UUID sessionId, String reason, Instant when);

    /**
     * Revokes every session of one user. Called when a retired refresh token reappears, because at
     * that point it is not knowable which holder is the legitimate one.
     */
    int revokeAllForUser(UUID userId, String reason, Instant when);

    int deleteExpired(Instant before);
}
