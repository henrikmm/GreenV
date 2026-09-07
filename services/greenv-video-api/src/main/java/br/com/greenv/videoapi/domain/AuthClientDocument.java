package br.com.greenv.videoapi.domain;

import java.time.Instant;

/**
 * A machine client of the {@code client_credentials} grant. The secret is shown once at creation
 * and only its BCrypt digest is kept.
 */
public record AuthClientDocument(
        String clientId,
        String clientSecretHash,
        String displayName,
        AuthRole role,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
