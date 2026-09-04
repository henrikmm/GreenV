package br.com.greenv.videoapi.token;

import br.com.greenv.videoapi.config.AuthProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.converter.RsaKeyConverters;

/**
 * The RSA key pair every token is signed with, loaded once at startup.
 *
 * <p>Terraform provisions it. Generating one per process would be wrong twice over: a restart would
 * invalidate every live token, and two replicas would sign with different keys and reject each
 * other's.
 *
 * <p>Three states, and no heuristics between them:
 *
 * <ol>
 *   <li>a key is configured - normal operation;
 *   <li>no key and {@code greenv.auth.ephemeral-key=true} - generates one and warns loudly. Only
 *       the local stack and the tests ask for this;
 *   <li>no key and no opt-in - <b>identity is disabled</b>: the auth routes answer 503, the JWK set
 *       is empty, and no token verifies. The static capture token is untouched, so a deployment
 *       that loses the secret degrades to what it was before this feature rather than falling back
 *       to a weaker key nobody noticed.
 * </ol>
 */
public final class SigningKey {

    private static final Logger log = LoggerFactory.getLogger(SigningKey.class);
    private static final int EPHEMERAL_KEY_SIZE = 2048;

    private final RSAKey rsaKey;
    private final boolean configured;

    private SigningKey(RSAKey rsaKey, boolean configured) {
        this.rsaKey = rsaKey;
        this.configured = configured;
    }

    public static SigningKey from(AuthProperties properties) {
        if (!properties.privateKey().isBlank()) {
            return new SigningKey(load(properties.privateKey()), true);
        }
        if (properties.ephemeralKey()) {
            log.warn(
                    "greenv.auth.ephemeral-key is on: signing with a key that dies with this process. "
                            + "Every restart invalidates every token, and a second replica would reject "
                            + "this one's. Never use this in a deployment.");
            return new SigningKey(generate(), true);
        }
        log.warn(
                "greenv.auth.private-key is not set: the identity provider is disabled. /v2/auth/** "
                        + "and /v2/oauth/token answer 503 and no JWT verifies. The static API token is "
                        + "unaffected.");
        // Generated but never used for anything a caller can reach, so the encoder and decoder beans
        // still construct without a special case.
        return new SigningKey(generate(), false);
    }

    /** False when no key was provisioned; every identity operation refuses in that state. */
    public boolean isConfigured() {
        return configured;
    }

    public RSAKey rsaKey() {
        return rsaKey;
    }

    /** The RFC 7638 thumbprint published in the JWK set and stamped into every token header. */
    public String keyId() {
        return rsaKey.getKeyID();
    }

    /**
     * Accepts either a PKCS#8 PEM or that PEM base64-encoded. Terraform sends the encoded form
     * because the value travels through the ARM API, a revision template and a container
     * environment, any one of which could normalise a newline and break the parse; the local stack
     * passes the PEM straight through.
     */
    private static RSAKey load(String raw) {
        try {
            String pem = raw.startsWith("-----BEGIN")
                    ? raw
                    : new String(
                            Base64.getDecoder().decode(raw.replaceAll("\\s", "")), StandardCharsets.UTF_8);

            // RsaKeyConverters.pkcs8() insists on "-----BEGIN PRIVATE KEY-----"; Terraform's
            // private_key_pem is PKCS#1 and would be rejected, which is why the resource must
            // expose private_key_pem_pkcs8.
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8()
                    .convert(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));

            var crt = (RSAPrivateCrtKey) privateKey;
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return describe(publicKey, privateKey);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "greenv.auth.private-key must be a PKCS#8 RSA PEM, optionally base64-encoded", e);
        }
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(EPHEMERAL_KEY_SIZE);
            KeyPair pair = generator.generateKeyPair();
            return describe((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("could not generate an ephemeral RSA key", e);
        }
    }

    /**
     * Built by hand rather than through {@code NimbusJwtEncoder.withKeyPair}, whose JWK carries
     * {@code key_ops: ["sign"]}. That survives {@code toPublicJWK()}, and a public verification key
     * advertising "sign" is wrong in a published JWK set.
     */
    private static RSAKey describe(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        try {
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("could not compute the signing key thumbprint", e);
        }
    }
}
