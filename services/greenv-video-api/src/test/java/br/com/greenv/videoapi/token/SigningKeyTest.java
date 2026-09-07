package br.com.greenv.videoapi.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.videoapi.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The key loader is the seam between Terraform and the running service, and a mistake there is only
 * visible at startup in production. These cover both forms Terraform and the local stack send.
 */
class SigningKeyTest {

    @Test
    void loadsThePkcs8PemTerraformProduces() {
        String pem = pkcs8Pem();

        var key = SigningKey.from(properties(pem));

        assertThat(key.isConfigured()).isTrue();
        assertThat(key.keyId()).isNotBlank();
    }

    /** Terraform base64-encodes the PEM so no newline can be lost on the way through Azure. */
    @Test
    void loadsTheSamePemBase64Encoded() {
        String pem = pkcs8Pem();
        String encoded = Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));

        var fromPem = SigningKey.from(properties(pem));
        var fromBase64 = SigningKey.from(properties(encoded));

        // Same key either way, so the two paths cannot drift into signing with different keys.
        assertThat(fromBase64.keyId()).isEqualTo(fromPem.keyId());
    }

    @Test
    void refusesAKeyItCannotParse() {
        assertThatThrownBy(() -> SigningKey.from(properties("-----BEGIN PRIVATE KEY-----\nnope\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    /**
     * The safety property the whole design rests on: no key and no explicit opt-in means identity
     * is off, not that a weaker key is quietly used.
     */
    @Test
    void reportsItselfUnconfiguredWithoutAKeyOrAnOptIn() {
        assertThat(SigningKey.from(properties("")).isConfigured()).isFalse();
    }

    @Test
    void generatesAKeyOnlyWhenAskedExplicitly() {
        var properties = new AuthProperties(
                "https://greenv.test", "greenv-video-api", "", true, null, null, null, null);

        assertThat(SigningKey.from(properties).isConfigured()).isTrue();
    }

    private static AuthProperties properties(String privateKey) {
        return new AuthProperties(
                "https://greenv.test",
                "greenv-video-api",
                privateKey,
                false,
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                Duration.ofHours(4),
                null);
    }

    private static String pkcs8Pem() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                            .encodeToString(encoded)
                    + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
