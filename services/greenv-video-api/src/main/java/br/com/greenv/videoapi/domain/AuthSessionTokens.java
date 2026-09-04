package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything one login or one rotation hands back. {@code refreshToken} and {@code fingerprint} are
 * the only copies that will ever exist - the database keeps their SHA-256 and nothing else.
 */
public record AuthSessionTokens(
        UUID sessionId,
        AuthUserDocument user,
        IssuedToken accessToken,
        String refreshToken,
        Instant refreshExpiresAt,
        String fingerprint,
        String csrfToken) {}
