package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.IssuedToken;
import br.com.greenv.videoapi.domain.TokenPrincipal;
import java.time.Duration;

/** Signs access tokens. The signature is what proves this application minted them. */
public interface AccessTokenIssuer {

    IssuedToken issue(TokenPrincipal principal, Duration lifetime);
}
