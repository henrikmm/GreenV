package br.com.greenv.videoapi.security;

import br.com.greenv.videoapi.domain.AuthRole;
import java.util.UUID;

/**
 * What the filter chain puts in the security context for a JWT-authenticated caller. The static
 * capture token still authenticates the plain {@code "capture-client"} string it always did.
 *
 * @param cookieAuthenticated true when the token arrived in a cookie rather than a header, which is
 *     the case CSRF protection has to cover
 */
public record AuthenticatedPrincipal(
        String subject,
        String displayName,
        AuthRole role,
        UUID sessionId,
        boolean machineClient,
        boolean cookieAuthenticated) {

    public UUID userId() {
        return machineClient ? null : UUID.fromString(subject);
    }
}
