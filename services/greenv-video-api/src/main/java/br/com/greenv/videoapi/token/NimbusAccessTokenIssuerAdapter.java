package br.com.greenv.videoapi.token;

import br.com.greenv.videoapi.config.AuthProperties;
import br.com.greenv.videoapi.domain.IssuedToken;
import br.com.greenv.videoapi.domain.TokenPrincipal;
import br.com.greenv.videoapi.port.AccessTokenIssuer;
import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs RS256 access tokens.
 *
 * <p>The signature is a SHA-256 digest of the header and payload encrypted with the private key, so
 * altering a single byte of a token makes verification fail. That is the integrity check; nothing
 * else is layered on top of it.
 */
@Component
public class NimbusAccessTokenIssuerAdapter implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final IdentifierGenerator identifiers;
    private final AuthProperties properties;
    private final SigningKey signingKey;
    private final Clock clock;

    public NimbusAccessTokenIssuerAdapter(
            JwtEncoder encoder,
            IdentifierGenerator identifiers,
            AuthProperties properties,
            SigningKey signingKey,
            Clock clock) {
        this.encoder = encoder;
        this.identifiers = identifiers;
        this.properties = properties;
        this.signingKey = signingKey;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(TokenPrincipal principal, Duration lifetime) {
        if (!signingKey.isConfigured()) {
            throw new ApplicationException(
                    FailureKind.DEPENDENCY_UNAVAILABLE,
                    "identity_provider_unconfigured",
                    "no signing key is provisioned, so no token can be issued");
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(lifetime);

        var claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(principal.subject())
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(identifiers.next().toString())
                .claim(TokenClaims.TOKEN_USE, TokenClaims.ACCESS)
                .claim(TokenClaims.ROLE, principal.role().authority())
                .claim(TokenClaims.DISPLAY_NAME, principal.displayName())
                .claim(TokenClaims.MACHINE_CLIENT, principal.machineClient());

        if (principal.sessionId() != null) {
            claims.claim(TokenClaims.SESSION_ID, principal.sessionId().toString());
        }
        if (principal.requiresFingerprint()) {
            Map<String, Object> confirmation = new LinkedHashMap<>();
            confirmation.put(TokenClaims.FINGERPRINT, principal.fingerprintHash());
            claims.claim(TokenClaims.CONFIRMATION, confirmation);
        }

        var header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKey.keyId())
                .type(TokenClaims.ACCESS_TOKEN_TYPE)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }
}
