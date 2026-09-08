package br.com.greenv.videoapi.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

@Component
final class ConfiguredApiTokenVerifier implements ApiTokenVerifier {

    private final byte[] expectedToken;

    ConfiguredApiTokenVerifier(ApiSecurityProperties properties) {
        expectedToken = properties.apiToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isValid(String candidate) {
        return candidate != null
                && MessageDigest.isEqual(expectedToken, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
