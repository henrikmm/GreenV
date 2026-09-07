package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.AuthClientDocument;
import java.time.Instant;
import java.util.Optional;

/** Persistent registry of machine clients for the {@code client_credentials} grant. */
public interface OAuthClientStore {

    AuthClientDocument insert(AuthClientDocument client);

    Optional<AuthClientDocument> findById(String clientId);

    void recordUse(String clientId, Instant when);
}
