package br.com.greenv.videoapi.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code allowedOrigins} is empty by default, which leaves cross-origin requests blocked. A browser
 * client such as the Flutter web capture build needs its exact origin listed here; CORS decides
 * which pages a browser lets read a response, it never replaces the Bearer token.
 */
@Validated
@ConfigurationProperties(prefix = "greenv.security")
public record ApiSecurityProperties(
        @NotBlank @Size(min = 32) String apiToken,
        List<String> allowedOrigins) {

    public ApiSecurityProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList();
    }

    public boolean hasAllowedOrigins() {
        return !allowedOrigins.isEmpty();
    }
}
