package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.AuthUserDocument;
import java.time.Instant;

/** Who the caller is. Deliberately carries no token: the browser's copy lives only in a cookie. */
public record AuthenticatedUserResponse(
        String userId,
        String email,
        String displayName,
        String role,
        Instant accessTokenExpiresAt) {

    static AuthenticatedUserResponse from(AuthUserDocument user, Instant accessTokenExpiresAt) {
        return new AuthenticatedUserResponse(
                user.userId().toString(),
                user.email(),
                user.displayName(),
                user.role().name(),
                accessTokenExpiresAt);
    }
}
