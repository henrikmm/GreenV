package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.port.JwkSetProvider;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the signing key.
 *
 * <p>This is the endpoint that answers "did our application issue this token?" for anyone but us:
 * the private key never leaves the service, so a signature that verifies against this key set could
 * only have been produced here.
 */
@RestController
public class JwkSetController {

    private final JwkSetProvider jwkSet;

    public JwkSetController(JwkSetProvider jwkSet) {
        this.jwkSet = jwkSet;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> keys() {
        return jwkSet.jwkSet();
    }
}
