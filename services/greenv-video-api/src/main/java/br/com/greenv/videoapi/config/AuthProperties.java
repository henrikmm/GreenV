package br.com.greenv.videoapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity provider settings.
 *
 * <p>{@code privateKey} is a PKCS#8 PEM, optionally base64-encoded. Nothing here throws on a
 * missing key: an unconfigured deployment starts with identity disabled rather than failing, so a
 * lost secret can never take the live capture pipeline down with it. See {@code SigningKey} for the
 * three states.
 */
@ConfigurationProperties("greenv.auth")
public record AuthProperties(
        String issuer,
        String audience,
        String privateKey,
        boolean ephemeralKey,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration clientCredentialsTtl,
        Cookie cookie) {

    public AuthProperties {
        issuer = blankTo(issuer, "https://greenv.local");
        audience = blankTo(audience, "greenv-video-api");
        privateKey = privateKey == null ? "" : privateKey.trim();
        accessTokenTtl = positiveOrDefault(accessTokenTtl, Duration.ofMinutes(15));
        refreshTokenTtl = positiveOrDefault(refreshTokenTtl, Duration.ofDays(7));
        clientCredentialsTtl = positiveOrDefault(clientCredentialsTtl, Duration.ofHours(4));
        cookie = cookie == null ? new Cookie("Lax", null) : cookie;
    }

    /**
     * Cookies are always {@code Secure} - there is no property to turn that off, because a session
     * cookie sent in the clear is a session anyone on the path can take. Browsers treat
     * {@code localhost} as a trustworthy origin, so this holds for the local stack too.
     *
     * <p>{@code sameSite} is a property only so that {@code None} has to be a deliberate,
     * reviewable act. Lax is right whenever the dashboard and the API share a registrable domain,
     * which the Vite dev proxy arranges locally.
     *
     * <p>{@code csrfDomain} widens one cookie and only one. The session cookies keep the
     * {@code __Host-} prefix, which forbids a domain outright, and they never needed one: the
     * browser sends them to this host by itself. The CSRF cookie is the opposite kind of thing —
     * it exists to be read by script on the dashboard's page, and when the dashboard is served
     * from a different subdomain a host-only cookie is invisible to it. Left unset, nothing
     * changes; that is the right answer whenever both are served from one host.
     */
    public record Cookie(String sameSite, String csrfDomain) {
        public Cookie {
            sameSite = blankTo(sameSite, "Lax");
            csrfDomain = csrfDomain == null || csrfDomain.isBlank() ? null : csrfDomain.trim();
        }
    }

    private static String blankTo(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.trim();
    }

    private static Duration positiveOrDefault(Duration candidate, Duration fallback) {
        return candidate == null || candidate.isZero() || candidate.isNegative() ? fallback : candidate;
    }
}
