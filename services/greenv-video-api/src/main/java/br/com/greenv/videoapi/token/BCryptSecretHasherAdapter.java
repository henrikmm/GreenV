package br.com.greenv.videoapi.token;

import br.com.greenv.videoapi.domain.TokenDigest;
import br.com.greenv.videoapi.port.SecretHasher;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt at cost 12, tagged {@code {bcrypt}} so every stored hash records which algorithm produced
 * it and a later move to another one is a new map entry rather than a migration of every row.
 *
 * <p>Chosen over Argon2 because Argon2PasswordEncoder needs BouncyCastle, which Boot's dependency
 * management does not pin - that would mean an unmanaged dependency to version by hand. BCrypt is
 * pure Java in {@code spring-security-crypto}, already on the classpath.
 */
@Component
public class BCryptSecretHasherAdapter implements SecretHasher {

    private static final int COST = 12;

    private final PasswordEncoder encoder =
            new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(COST)));

    /**
     * A hash of a value nobody knows, computed once at startup. Verifying against it on a missing
     * account costs the same as a real check, so response time does not say which accounts exist.
     * Computed rather than hardcoded so it is guaranteed to be a hash this encoder will actually
     * spend time on.
     */
    private final String absentHash = encoder.encode(TokenDigest.randomToken());

    @Override
    public String hash(String rawSecret) {
        return encoder.encode(rawSecret);
    }

    @Override
    public boolean matches(String rawSecret, String storedHash) {
        String candidate = rawSecret == null ? "" : rawSecret;
        if (storedHash == null || storedHash.isBlank()) {
            encoder.matches(candidate, absentHash);
            return false;
        }
        return encoder.matches(candidate, storedHash);
    }
}
