package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.AuthClientDocument;
import br.com.greenv.videoapi.domain.AuthRole;
import br.com.greenv.videoapi.domain.AuthUserDocument;

/** Inbound operations that create identities. Guarded so only an operator already trusted can call. */
public interface IdentityAdminUseCase {

    AuthUserDocument createUser(String email, String password, String displayName, AuthRole role);

    /**
     * Creates a machine client. The returned {@code secret} is the only copy that will ever exist;
     * the store keeps its hash.
     */
    ClientRegistration createClient(String displayName, AuthRole role);

    record ClientRegistration(AuthClientDocument client, String secret) {}
}
