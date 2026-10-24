package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.AuthProperties;
import br.com.greenv.videoapi.domain.AuthClientDocument;
import br.com.greenv.videoapi.domain.AuthRole;
import br.com.greenv.videoapi.domain.AuthSessionDocument;
import br.com.greenv.videoapi.domain.AuthSessionTokens;
import br.com.greenv.videoapi.domain.AuthUserDocument;
import br.com.greenv.videoapi.domain.IssuedToken;
import br.com.greenv.videoapi.domain.TokenPrincipal;
import br.com.greenv.videoapi.port.AccessTokenIssuer;
import br.com.greenv.videoapi.port.AuthSessionStore;
import br.com.greenv.videoapi.port.AuthenticationUseCase;
import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.port.OAuthClientStore;
import br.com.greenv.videoapi.port.SecretHasher;
import br.com.greenv.videoapi.port.UserStore;
import br.com.greenv.videoapi.domain.TokenDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService implements AuthenticationUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * One message for a wrong email and a wrong password alike. Telling them apart turns the login
     * form into an oracle for which accounts exist.
     */
    private static final String INVALID_CREDENTIALS = "email or password is not valid";

    /**
     * The one demonstration account. {@code .local} is reserved for local networks, so no real
     * mailbox can ever hold this address and nobody can be provisioned under it by accident.
     */
    public static final String DEMO_EMAIL = "demo@greenv.local";

    private static final String DEMO_DISPLAY_NAME = "Demonstração";

    private final UserStore users;
    private final AuthSessionStore sessions;
    private final OAuthClientStore clients;
    private final SecretHasher hasher;
    private final AccessTokenIssuer tokenIssuer;
    private final IdentifierGenerator identifiers;
    private final AuthProperties properties;
    private final Clock clock;
    private final boolean demoLoginEnabled;

    public AuthenticationService(
            UserStore users,
            AuthSessionStore sessions,
            OAuthClientStore clients,
            SecretHasher hasher,
            AccessTokenIssuer tokenIssuer,
            IdentifierGenerator identifiers,
            AuthProperties properties,
            Clock clock,
            @Value("${greenv.demo-login.enabled:false}") boolean demoLoginEnabled) {
        this.users = users;
        this.sessions = sessions;
        this.clients = clients;
        this.hasher = hasher;
        this.tokenIssuer = tokenIssuer;
        this.identifiers = identifiers;
        this.properties = properties;
        this.clock = clock;
        this.demoLoginEnabled = demoLoginEnabled;
    }

    @Override
    @Transactional(noRollbackFor = ApplicationException.class)
    public AuthSessionTokens login(String email, String password) {
        String normalized = AuthUserDocument.normalizeEmail(email);
        Optional<AuthUserDocument> found = users.findByNormalizedEmail(normalized);

        if (found.isEmpty()) {
            // Spend the hashing time anyway, so a missing account and a wrong password take the
            // same wall clock.
            hasher.matches(password == null ? "" : password, null);
            throw unauthorized();
        }

        AuthUserDocument user = found.get();
        // The demonstration account's hash is of a secret nobody was ever told, so the first test
        // already fails. The role check is the second lock: it holds even if someone sets that
        // account a real password, and it costs the same wall clock as any other refusal.
        if (!hasher.matches(password == null ? "" : password, user.passwordHash())
                || !user.isActive()
                || user.role() == AuthRole.DEMO) {
            throw unauthorized();
        }

        Instant now = clock.instant();
        users.recordLogin(user.userId(), now);
        return openSession(user, now, now.plus(properties.refreshTokenTtl()));
    }

    /**
     * Not transactional on purpose: the account is created with a statement of its own, so two
     * visitors pressing the button at the same moment cannot leave a transaction aborted by the
     * loser's duplicate key. The loser simply reads the row the winner wrote.
     */
    @Override
    public AuthSessionTokens demoLogin() {
        if (!demoLoginEnabled) {
            throw new ApplicationException(
                    FailureKind.NOT_FOUND, "not_found", "demonstration login is not enabled");
        }

        AuthUserDocument user = demoUser();
        // A row under the demo address that is anything other than an active DEMO account was
        // put there by hand. Signing a visitor into it could hand them an operator's writes.
        if (user.role() != AuthRole.DEMO || !user.isActive()) {
            log.warn("refused demonstration login: {} is not an active DEMO account", DEMO_EMAIL);
            throw new ApplicationException(
                    FailureKind.FORBIDDEN,
                    "demo_account_misconfigured",
                    "the demonstration account is not available");
        }

        Instant now = clock.instant();
        users.recordLogin(user.userId(), now);
        return openSession(user, now, now.plus(properties.refreshTokenTtl()));
    }

    private AuthUserDocument demoUser() {
        String normalized = AuthUserDocument.normalizeEmail(DEMO_EMAIL);
        Optional<AuthUserDocument> existing = users.findByNormalizedEmail(normalized);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.instant();
        try {
            return users.insert(new AuthUserDocument(
                    identifiers.next(),
                    DEMO_EMAIL,
                    normalized,
                    // A digest of a random value that is discarded here: no password exists that
                    // matches it, so /v2/auth/login can never open this account.
                    hasher.hash(TokenDigest.randomToken()),
                    DEMO_DISPLAY_NAME,
                    AuthRole.DEMO,
                    AuthUserDocument.STATUS_ACTIVE,
                    now,
                    now,
                    null));
        } catch (ApplicationException concurrentInsert) {
            return users.findByNormalizedEmail(normalized).orElseThrow(() -> concurrentInsert);
        }
    }

    @Override
    @Transactional(noRollbackFor = ApplicationException.class)
    public AuthSessionTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw unauthorized();
        }

        String presentedHash = TokenDigest.sha256Hex(refreshToken);
        AuthSessionDocument session = sessions
                .findByRefreshTokenHash(presentedHash)
                .orElseThrow(AuthenticationService::unauthorized);

        Instant now = clock.instant();

        // A refresh token that was already exchanged can only be in circulation because it was
        // copied. There is no way to tell the thief from the legitimate holder, so both lose the
        // session: every login of that user is revoked.
        if (session.replacedBy() != null) {
            int revoked = sessions.revokeAllForUser(
                    session.userId(), AuthSessionDocument.REVOKED_REUSE_DETECTED, now);
            log.warn(
                    "refresh token reuse detected for user {}; revoked {} session(s)",
                    session.userId(),
                    revoked);
            throw unauthorized();
        }

        if (!session.isUsable(now)) {
            throw unauthorized();
        }

        AuthUserDocument user = users.findById(session.userId())
                .filter(AuthUserDocument::isActive)
                .orElseThrow(AuthenticationService::unauthorized);

        // The successor inherits the original expiry rather than getting a fresh seven days,
        // so an actively used session still ends when the login it descends from does.
        AuthSessionTokens rotated = openSession(user, now, session.expiresAt());
        sessions.markRotated(session.sessionId(), rotated.sessionId(), now);
        return rotated;
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        // Keyed off the refresh token rather than the access token, so signing out still revokes
        // the session after the fifteen-minute access token has already expired.
        sessions.findByRefreshTokenHash(TokenDigest.sha256Hex(refreshToken))
                .ifPresent(session -> sessions.revokeAllForUser(
                        session.userId(), AuthSessionDocument.REVOKED_LOGOUT, clock.instant()));
    }

    @Override
    @Transactional(noRollbackFor = ApplicationException.class)
    public IssuedToken authenticateClient(String clientId, String clientSecret) {
        Optional<AuthClientDocument> found =
                clientId == null ? Optional.empty() : clients.findById(clientId);

        if (found.isEmpty()) {
            hasher.matches(clientSecret == null ? "" : clientSecret, null);
            throw unauthorized();
        }

        AuthClientDocument client = found.get();
        if (!hasher.matches(clientSecret == null ? "" : clientSecret, client.clientSecretHash())
                || !client.isActive()) {
            throw unauthorized();
        }

        Instant now = clock.instant();
        clients.recordUse(client.clientId(), now);

        // No session row and no fingerprint: a machine has no cookie jar, and there is no refresh
        // chain to revoke. The four-hour lifetime is the whole control.
        var principal = new TokenPrincipal(
                client.clientId(), client.displayName(), client.role(), null, null, true);
        return tokenIssuer.issue(principal, properties.clientCredentialsTtl());
    }

    @Override
    public boolean isSessionLive(UUID sessionId) {
        return sessions.findById(sessionId)
                .map(session -> session.isUsable(clock.instant()))
                .orElse(false);
    }

    @Override
    public AuthUserDocument requireUser(UUID userId) {
        return users.findById(userId)
                .filter(AuthUserDocument::isActive)
                .orElseThrow(AuthenticationService::unauthorized);
    }

    /**
     * Mints one session: a signed access token bound to a fresh fingerprint, an opaque refresh
     * token, and a CSRF value. Only the digests of the refresh token and the fingerprint reach the
     * database.
     */
    private AuthSessionTokens openSession(AuthUserDocument user, Instant now, Instant expiresAt) {
        UUID sessionId = identifiers.next();
        String refreshToken = TokenDigest.randomToken();
        String fingerprint = TokenDigest.randomToken();
        String fingerprintHash = TokenDigest.sha256Hex(fingerprint);

        sessions.insert(new AuthSessionDocument(
                sessionId,
                user.userId(),
                TokenDigest.sha256Hex(refreshToken),
                fingerprintHash,
                now,
                expiresAt,
                now,
                null,
                null,
                null));

        var principal = new TokenPrincipal(
                user.userId().toString(),
                user.displayName(),
                user.role(),
                sessionId,
                fingerprintHash,
                false);
        IssuedToken accessToken = tokenIssuer.issue(principal, properties.accessTokenTtl());

        return new AuthSessionTokens(
                sessionId,
                user,
                accessToken,
                refreshToken,
                expiresAt,
                fingerprint,
                TokenDigest.randomToken());
    }

    private static ApplicationException unauthorized() {
        return new ApplicationException(FailureKind.UNAUTHORIZED, "invalid_credentials", INVALID_CREDENTIALS);
    }
}
