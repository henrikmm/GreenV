package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One login. Holds the SHA-256 of the refresh token rather than the token, and the SHA-256 of the
 * fingerprint the access token is bound to.
 *
 * <p>{@code replacedBy} carries the rotation chain: once a refresh token has been exchanged its row
 * points at the successor. Presenting that retired token again is proof it was copied, and the
 * whole chain is revoked.
 */
public record AuthSessionDocument(
        UUID sessionId,
        UUID userId,
        String refreshTokenHash,
        String fingerprintHash,
        Instant issuedAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        String revokedReason,
        UUID replacedBy) {

    public static final String REVOKED_LOGOUT = "logout";
    public static final String REVOKED_ROTATED = "rotated";
    public static final String REVOKED_REUSE_DETECTED = "reuse_detected";

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isUsable(Instant now) {
        return !isRevoked() && !isExpired(now) && replacedBy == null;
    }
}
