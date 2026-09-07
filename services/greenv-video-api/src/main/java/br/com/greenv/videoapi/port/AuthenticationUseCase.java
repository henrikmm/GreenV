package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.AuthSessionTokens;
import br.com.greenv.videoapi.domain.AuthUserDocument;
import br.com.greenv.videoapi.domain.IssuedToken;
import java.util.UUID;

/** Inbound application operations for issuing and retiring credentials. */
public interface AuthenticationUseCase {

    /** Verifies email and password and opens a session. Throws on any failure, never explaining which. */
    AuthSessionTokens login(String email, String password);

    /**
     * Exchanges a refresh token for a fresh pair. Presenting a token that was already exchanged
     * revokes every session of that user.
     */
    AuthSessionTokens refresh(String refreshToken);

    /** Ends a session from its refresh token, which outlives the access token. */
    void logout(String refreshToken);

    /** The {@code client_credentials} grant: one token, no refresh chain. */
    IssuedToken authenticateClient(String clientId, String clientSecret);

    /**
     * Whether the session behind an access token is still open. An access token is signed for
     * fifteen minutes and cannot be unsigned, so this is what makes logout and reuse-detection
     * revocation take effect immediately rather than a quarter of an hour later. Tokens from the
     * client_credentials grant carry no session and skip it.
     */
    boolean isSessionLive(UUID sessionId);

    AuthUserDocument requireUser(UUID userId);
}
