package br.com.greenv.videoapi.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The SHA-256 the refresh token and the fingerprint are stored and compared as.
 *
 * <p>The token itself is never persisted: only this digest is. A database dump therefore hands an
 * attacker nothing presentable, and comparison goes through {@link MessageDigest#isEqual}, the same
 * constant-time primitive the static API token already uses.
 */
public final class TokenDigest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private TokenDigest() {}

    /** 256 bits of entropy, base64url without padding - safe in a cookie and in a form body. */
    public static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** Constant-time comparison of two hex digests. */
    public static boolean digestsMatch(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
