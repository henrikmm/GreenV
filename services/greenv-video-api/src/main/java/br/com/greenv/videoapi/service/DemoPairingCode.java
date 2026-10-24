package br.com.greenv.videoapi.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The demo's pairing code, checked the one way every demo write checks it.
 *
 * <p>A speed bump, not an identity: one shared secret the operator types into the phone and the
 * site. It is compared in constant time, so the time an answer takes says nothing about how much
 * of a guess was right. No code at all is a 401, a wrong one a 403, so a client can tell "ask the
 * operator for the code" from "the code you have is wrong".
 */
final class DemoPairingCode {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] code;

    DemoPairingCode(String code) {
        this.code = code.strip().getBytes(StandardCharsets.UTF_8);
    }

    /** Whether this {@code Authorization} header carries the code. Never throws. */
    boolean matches(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presented = authorization.substring(BEARER_PREFIX.length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(presented, code);
    }

    /** 401 when no code was presented at all, 403 when the one presented is wrong. */
    void require(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                || authorization.substring(BEARER_PREFIX.length()).isBlank()) {
            throw new ApplicationException(
                    FailureKind.UNAUTHORIZED, "pairing_code_required", "missing pairing code");
        }
        if (!matches(authorization)) {
            throw new ApplicationException(
                    FailureKind.FORBIDDEN, "pairing_code_wrong", "wrong pairing code");
        }
    }

    /** Never prints the code. */
    @Override
    public String toString() {
        return "DemoPairingCode[<set>]";
    }
}
