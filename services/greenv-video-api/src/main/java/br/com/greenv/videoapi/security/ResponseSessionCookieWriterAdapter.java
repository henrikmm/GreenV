package br.com.greenv.videoapi.security;

import br.com.greenv.videoapi.config.AuthProperties;
import br.com.greenv.videoapi.domain.AuthSessionTokens;
import br.com.greenv.videoapi.port.SessionCookieWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class ResponseSessionCookieWriterAdapter implements SessionCookieWriter {

    private static final List<String> ALL_COOKIES =
            List.of(AuthCookies.ACCESS, AuthCookies.REFRESH, AuthCookies.FINGERPRINT, AuthCookies.CSRF);

    private final AuthProperties properties;

    public ResponseSessionCookieWriterAdapter(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<String> open(AuthSessionTokens tokens) {
        Duration accessTtl = properties.accessTokenTtl();
        Duration refreshTtl = properties.refreshTokenTtl();

        List<String> opened = new ArrayList<>(List.of(
                cookie(AuthCookies.ACCESS, tokens.accessToken().value(), accessTtl),
                cookie(AuthCookies.REFRESH, tokens.refreshToken(), refreshTtl),
                // Outlives the access token, so a refresh can rebind without a fresh login.
                cookie(AuthCookies.FINGERPRINT, tokens.fingerprint(), refreshTtl),
                cookie(AuthCookies.CSRF, tokens.csrfToken(), refreshTtl)));

        // Anyone who signed in before the CSRF cookie took a domain is holding the host-only one
        // as well, and the browser sends both. Expiring it here is the only way it ever goes:
        // `clear` writes the domain now, and a cookie with a domain cannot expire one without.
        AuthCookies.clearHostOnlyCsrfTwin(properties)
                .map(ResponseCookie::toString)
                .ifPresent(opened::add);
        return List.copyOf(opened);
    }

    @Override
    public List<String> close() {
        List<String> cleared = new ArrayList<>(ALL_COOKIES.stream()
                .map(name -> AuthCookies.clear(properties, name).toString())
                .toList());
        AuthCookies.clearHostOnlyCsrfTwin(properties)
                .map(ResponseCookie::toString)
                .ifPresent(cleared::add);
        return List.copyOf(cleared);
    }

    private String cookie(String name, String value, Duration maxAge) {
        ResponseCookie responseCookie = AuthCookies.set(properties, name, value, maxAge);
        return responseCookie.toString();
    }
}
