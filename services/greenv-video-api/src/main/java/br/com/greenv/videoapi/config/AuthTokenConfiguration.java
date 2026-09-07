package br.com.greenv.videoapi.config;

import br.com.greenv.videoapi.token.SigningKey;
import br.com.greenv.videoapi.token.TokenClaims;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The signing key and the encoder/decoder pair built on it.
 *
 * <p>These beans are declared by hand rather than pulled in with
 * {@code spring-boot-starter-oauth2-resource-server}, whose autoconfiguration would build a
 * competing decoder and its own filter chain. This service owns its {@code SecurityFilterChain}.
 */
@Configuration
public class AuthTokenConfiguration {

    @Bean
    SigningKey jwtSigningKey(AuthProperties properties) {
        return SigningKey.from(properties);
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKey signingKey) {
        var source = new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey.rsaKey()));
        return new NimbusJwtEncoder(source);
    }

    /**
     * Validates, in this order: the RS256 signature against our own public key, then {@code exp}
     * and {@code nbf}, then {@code iss}, then {@code aud}. A token that is well formed and correctly
     * signed by somebody else's key fails the first check; one minted for another audience fails the
     * last.
     */
    @Bean
    JwtDecoder jwtDecoder(SigningKey signingKey, AuthProperties properties) {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withPublicKey(signingKey.rsaKey().toRSAPublicKey()).build();
        } catch (Exception e) {
            throw new IllegalStateException("could not derive the public key from the signing key", e);
        }

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.issuer()),
                new JwtAudienceValidator(properties.audience()),
                // RFC 9068. Without it a token minted for some other purpose but signed by the same
                // key could be replayed as an access token.
                new JwtTypeValidator(TokenClaims.ACCESS_TOKEN_TYPE)));
        return decoder;
    }
}
