package br.com.greenv.videoapi.domain;

import java.util.UUID;

/**
 * Who a verified access token says is calling. {@code sessionId} is null for the
 * {@code client_credentials} grant, which has no refresh chain to revoke.
 */
public record TokenPrincipal(
        String subject,
        String displayName,
        AuthRole role,
        UUID sessionId,
        String fingerprintHash,
        boolean machineClient) {

    public boolean requiresFingerprint() {
        return fingerprintHash != null && !fingerprintHash.isBlank();
    }
}
