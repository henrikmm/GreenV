package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A person who can sign in. {@code passwordHash} is a BCrypt digest; the password itself is never
 * stored, logged or returned.
 */
public record AuthUserDocument(
        UUID userId,
        String email,
        String emailNormalized,
        String passwordHash,
        String displayName,
        AuthRole role,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    /** Email is matched case-insensitively, so a single normalised form is what the unique key uses. */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
