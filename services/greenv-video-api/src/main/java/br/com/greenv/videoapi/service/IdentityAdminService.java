package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.domain.AuthClientDocument;
import br.com.greenv.videoapi.domain.AuthRole;
import br.com.greenv.videoapi.domain.AuthUserDocument;
import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.port.IdentityAdminUseCase;
import br.com.greenv.videoapi.port.OAuthClientStore;
import br.com.greenv.videoapi.port.SecretHasher;
import br.com.greenv.videoapi.port.UserStore;
import br.com.greenv.videoapi.domain.TokenDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class IdentityAdminService implements IdentityAdminUseCase {

    private static final int MIN_PASSWORD_LENGTH = 12;

    /** BCrypt hashes at most 72 bytes and silently ignores the rest. Reject rather than mislead. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserStore users;
    private final OAuthClientStore clients;
    private final SecretHasher hasher;
    private final IdentifierGenerator identifiers;
    private final Clock clock;

    public IdentityAdminService(
            UserStore users,
            OAuthClientStore clients,
            SecretHasher hasher,
            IdentifierGenerator identifiers,
            Clock clock) {
        this.users = users;
        this.clients = clients;
        this.hasher = hasher;
        this.identifiers = identifiers;
        this.clock = clock;
    }

    @Override
    public AuthUserDocument createUser(String email, String password, String displayName, AuthRole role) {
        String normalized = AuthUserDocument.normalizeEmail(email);
        if (normalized.isBlank() || !normalized.contains("@")) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_email", "email must be a valid address");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "weak_password",
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "password_too_long",
                    "password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (password.equalsIgnoreCase(normalized)) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "weak_password", "password must not be the email address");
        }

        Instant now = clock.instant();
        return users.insert(new AuthUserDocument(
                identifiers.next(),
                email.trim(),
                normalized,
                hasher.hash(password),
                displayName == null || displayName.isBlank() ? email.trim() : displayName.trim(),
                role,
                AuthUserDocument.STATUS_ACTIVE,
                now,
                now,
                null));
    }

    @Override
    public ClientRegistration createClient(String displayName, AuthRole role) {
        if (displayName == null || displayName.isBlank()) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_client_name", "displayName is required");
        }

        Instant now = clock.instant();
        // The secret is returned once here and never again: the store keeps only its BCrypt digest.
        String secret = TokenDigest.randomToken();
        String clientId = "gv-" + identifiers.next().toString().replace("-", "").substring(0, 20)
                .toLowerCase(Locale.ROOT);

        AuthClientDocument client = clients.insert(new AuthClientDocument(
                clientId,
                hasher.hash(secret),
                displayName.trim(),
                role,
                AuthClientDocument.STATUS_ACTIVE,
                now,
                now,
                null));

        return new ClientRegistration(client, secret);
    }
}
