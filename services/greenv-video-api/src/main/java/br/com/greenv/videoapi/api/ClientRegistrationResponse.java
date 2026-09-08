package br.com.greenv.videoapi.api;

import java.time.Instant;

/**
 * The one and only time {@code clientSecret} is readable. The store keeps a BCrypt digest of it and
 * cannot reproduce the value, so a lost secret means issuing a new client.
 */
public record ClientRegistrationResponse(
        String clientId,
        String clientSecret,
        String displayName,
        String role,
        Instant createdAt) {
}
