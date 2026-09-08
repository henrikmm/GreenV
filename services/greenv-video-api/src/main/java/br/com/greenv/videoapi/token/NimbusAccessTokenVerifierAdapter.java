package br.com.greenv.videoapi.token;

import br.com.greenv.videoapi.domain.AuthRole;
import br.com.greenv.videoapi.domain.TokenPrincipal;
import br.com.greenv.videoapi.port.AccessTokenVerifier;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Verifies a token's signature, issuer, audience and expiry before believing a word of it.
 *
 * <p>The decoder built in {@code AuthTokenConfiguration} carries the issuer and audience
 * validators, so a well-formed token minted by anything other than this application is rejected
 * here - that is the "was it really our application" check.
 *
 * <p>Returns empty rather than throwing so the filter can fall through to the next scheme.
 */
@Component
public class NimbusAccessTokenVerifierAdapter implements AccessTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(NimbusAccessTokenVerifierAdapter.class);

    private final JwtDecoder decoder;
    private final SigningKey signingKey;

    public NimbusAccessTokenVerifierAdapter(JwtDecoder decoder, SigningKey signingKey) {
        this.decoder = decoder;
        this.signingKey = signingKey;
    }

    @Override
    public Optional<TokenPrincipal> verify(String token) {
        if (!signingKey.isConfigured() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException e) {
            // Expected for the static capture token, which is not a JWT at all.
            log.debug("rejected an access token: {}", e.getMessage());
            return Optional.empty();
        }

        if (!TokenClaims.ACCESS.equals(jwt.getClaimAsString(TokenClaims.TOKEN_USE))) {
            return Optional.empty();
        }

        AuthRole role;
        try {
            String authority = jwt.getClaimAsString(TokenClaims.ROLE);
            role = AuthRole.of(authority.substring("ROLE_".length()));
        } catch (RuntimeException e) {
            log.debug("rejected an access token with an unusable role claim");
            return Optional.empty();
        }

        UUID sessionId = null;
        String rawSession = jwt.getClaimAsString(TokenClaims.SESSION_ID);
        if (rawSession != null && !rawSession.isBlank()) {
            try {
                sessionId = UUID.fromString(rawSession);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        String fingerprintHash = null;
        Map<String, Object> confirmation = jwt.getClaimAsMap(TokenClaims.CONFIRMATION);
        if (confirmation != null) {
            Object value = confirmation.get(TokenClaims.FINGERPRINT);
            fingerprintHash = value == null ? null : value.toString();
        }

        return Optional.of(new TokenPrincipal(
                jwt.getSubject(),
                jwt.getClaimAsString(TokenClaims.DISPLAY_NAME),
                role,
                sessionId,
                fingerprintHash,
                Boolean.TRUE.equals(jwt.getClaim(TokenClaims.MACHINE_CLIENT))));
    }
}
