package br.com.greenv.videoapi.security;

import br.com.greenv.videoapi.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * The cookies a browser session runs on.
 *
 * <p>No cookie can be made impossible to copy - any bearer credential works for whoever holds it.
 * What these attributes do is make a copy hard to obtain and useless once obtained:
 *
 * <ul>
 *   <li>{@code HttpOnly} keeps JavaScript, and so any XSS, from reading the token at all.
 *   <li>The {@code __Host-} prefix forces {@code Secure} and {@code Path=/} and forbids a
 *       {@code Domain} attribute, so no sibling subdomain can set or shadow one of these.
 *   <li>{@code SameSite=Lax} keeps a cross-site page from making the browser send them.
 *   <li>The fingerprint cookie is the one that makes a stolen access token worthless: the token
 *       itself carries only the SHA-256 of this value, so a token lifted from a log or a proxy is
 *       not usable without the cookie it was bound to.
 * </ul>
 */
public final class AuthCookies {

    public static final String ACCESS = "__Host-greenv_at";
    public static final String REFRESH = "__Host-greenv_rt";
    public static final String FINGERPRINT = "__Host-greenv_fgp";

    /** Deliberately readable by JavaScript: the SPA must echo it back in {@code X-CSRF-Token}. */
    public static final String CSRF = "greenv_csrf";

    public static final String CSRF_HEADER = "X-CSRF-Token";

    private AuthCookies() {}

    public static ResponseCookie set(
            AuthProperties properties, String name, String value, Duration maxAge) {
        return builder(properties, name, value).maxAge(maxAge).build();
    }

    public static ResponseCookie clear(AuthProperties properties, String name) {
        return builder(properties, name, "").maxAge(Duration.ZERO).build();
    }

    public static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static ResponseCookie.ResponseCookieBuilder builder(
            AuthProperties properties, String name, String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                // The __Host- prefix is only honoured with these exact three, and no domain.
                .httpOnly(!CSRF.equals(name))
                // Never configurable: a session cookie sent in the clear is a session anyone
                // on the path can take. __Host- requires it too.
                .secure(true)
                .path("/")
                .sameSite(properties.cookie().sameSite());

        // Only the CSRF cookie, and only when configured. A dashboard on a sibling subdomain
        // cannot read a host-only cookie, and a token it cannot read is a token it cannot echo:
        // every write then fails the double-submit check and answers 401, which reads as a login
        // problem and is not one. The session cookies must never take a domain — it would strip
        // the __Host- prefix that stops a sibling subdomain shadowing them.
        String domain = properties.cookie().csrfDomain();
        if (CSRF.equals(name) && domain != null) {
            builder.domain(domain);
        }
        return builder;
    }
}
