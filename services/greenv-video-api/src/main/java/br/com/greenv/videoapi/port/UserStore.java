package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.AuthUserDocument;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistent user directory. Implementations may use any transactional database. */
public interface UserStore {

    AuthUserDocument insert(AuthUserDocument user);

    Optional<AuthUserDocument> findByNormalizedEmail(String emailNormalized);

    Optional<AuthUserDocument> findById(UUID userId);

    void recordLogin(UUID userId, Instant when);
}
