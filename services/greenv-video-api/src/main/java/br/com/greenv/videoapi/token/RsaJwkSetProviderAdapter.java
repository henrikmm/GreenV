package br.com.greenv.videoapi.token;

import br.com.greenv.videoapi.port.JwkSetProvider;
import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Publishes the public half of the signing key.
 *
 * <p>This is what an asymmetric algorithm buys: anyone can confirm a GreenV token came from GreenV
 * without holding the secret that mints them, which a shared HMAC secret can never offer.
 */
@Component
public class RsaJwkSetProviderAdapter implements JwkSetProvider {

    private final Map<String, Object> jwkSet;

    public RsaJwkSetProviderAdapter(SigningKey signingKey) {
        // toPublicJWK() drops every private parameter; only the modulus and exponent survive.
        this.jwkSet = signingKey.isConfigured()
                ? new JWKSet(signingKey.rsaKey().toPublicJWK()).toJSONObject()
                : new JWKSet().toJSONObject();
    }

    @Override
    public Map<String, Object> jwkSet() {
        return jwkSet;
    }
}
