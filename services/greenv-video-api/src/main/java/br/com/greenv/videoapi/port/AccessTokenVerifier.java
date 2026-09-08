package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.TokenPrincipal;
import java.util.Optional;

/**
 * Verifies an access token's signature, issuer, audience and expiry. Returns empty for anything it
 * cannot vouch for rather than throwing, so a filter can fall through to the next scheme.
 */
public interface AccessTokenVerifier {

    Optional<TokenPrincipal> verify(String token);
}
