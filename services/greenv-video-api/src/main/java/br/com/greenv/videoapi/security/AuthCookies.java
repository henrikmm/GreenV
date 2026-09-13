package br.com.greenv.videoapi.security;

import br.com.greenv.videoapi.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        return readAll(request, name).stream().findFirst();
    }

    /**
     * Every cookie under this name, because a browser can be holding more than one.
     *
     * <p>Cookies are keyed by name, domain and path, so {@code greenv_csrf} host-only and
     * {@code greenv_csrf} on the registrable domain are two cookies with one name, and the
     * browser sends both. Widening this cookie so the dashboard could read it therefore left
     * every browser that had logged in before holding a stale twin: the page echoed one value,
     * the server compared the other, and every write answered 401 exactly as it had before.
     *
     * <p>Reading only the first is what made that a silent mismatch. The double-submit check
     * asks whether the caller could read the cookie, and any one of them answers that.
     */
    public static List<String> readAll(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(2);
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                values.add(cookie.getValue());
            }
        }
        return List.copyOf(values);
    }

    /**
     * Kills the host-only twin of the CSRF cookie, once the real one carries a domain.
     *
     * <p>Without this the duplicate outlives every login: {@link #clear} now writes the domain
     * too, and a cookie with a domain cannot expire one without. It would sit there until
     * somebody cleared their site data.
     */
    public static Optional<ResponseCookie> clearHostOnlyCsrfTwin(AuthProperties properties) {
        if (properties.cookie().csrfDomain() == null) {
            return Optional.empty();
        }
        return Optional.of(ResponseCookie.from(CSRF, "")
                .secure(true)
                .path("/")
                .sameSite(properties.cookie().sameSite())
                .maxAge(Duration.ZERO)
                .build());
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
