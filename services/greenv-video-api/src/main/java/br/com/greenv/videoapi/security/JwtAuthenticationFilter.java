package br.com.greenv.videoapi.security;

import br.com.greenv.videoapi.domain.TokenDigest;
import br.com.greenv.videoapi.domain.TokenPrincipal;
import br.com.greenv.videoapi.port.AccessTokenVerifier;
import br.com.greenv.videoapi.port.AuthenticationUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a caller from an RS256 access token, either in the {@code Authorization} header or
 * in the session cookie.
 *
 * <p>It runs after {@link BearerTokenAuthenticationFilter} and does nothing if that one already
 * authenticated the request, so the static capture token keeps working exactly as before. Like it,
 * this filter never rejects: an unauthenticated request falls through to the authorization filter,
 * which raises the 401 that {@link ApiAuthenticationEntryPoint} renders.
 */
@Component
final class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS");

    private final AccessTokenVerifier verifier;
    private final AuthenticationUseCase authentication;

    JwtAuthenticationFilter(AccessTokenVerifier verifier, AuthenticationUseCase authentication) {
        this.verifier = verifier;
        this.authentication = authentication;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (unauthenticated()) {
            headerToken(request)
                    .flatMap(verifier::verify)
                    .filter(this::sessionIsLive)
                    .ifPresentOrElse(
                            principal -> authenticate(principal, false),
                            () -> authenticateFromCookie(request));
        }
        filterChain.doFilter(request, response);
    }

    /**
     * This filter sits just before the authorization filter, which is after Spring's
     * AnonymousAuthenticationFilter - so the context is never actually null by the time we run. An
     * anonymous token means nothing has authenticated yet.
     */
    private static boolean unauthenticated() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current == null || current instanceof AnonymousAuthenticationToken;
    }

    private void authenticateFromCookie(HttpServletRequest request) {
        Optional<String> token = AuthCookies.read(request, AuthCookies.ACCESS);
        if (token.isEmpty()) {
            return;
        }
        Optional<TokenPrincipal> verified = token.flatMap(verifier::verify);
        if (verified.isEmpty()) {
            return;
        }
        TokenPrincipal principal = verified.get();

        if (!sessionIsLive(principal)) {
            return;
        }

        // The token is bound to a fingerprint held in a second HttpOnly cookie. Presenting the
        // token without it means the two were not obtained together, which is what a copied token
        // looks like.
        if (principal.requiresFingerprint()) {
            Optional<String> fingerprint = AuthCookies.read(request, AuthCookies.FINGERPRINT);
            if (fingerprint.isEmpty()
                    || !TokenDigest.digestsMatch(
                            TokenDigest.sha256Hex(fingerprint.get()), principal.fingerprintHash())) {
                log.debug("rejected a cookie session whose access token is not bound to its fingerprint");
                return;
            }
        }

        // SameSite=Lax already blocks the cross-site navigations that carry a cookie, but it is one
        // browser setting away from being the only defence. Double-submit closes it: a cross-site
        // page cannot read greenv_csrf to echo it back.
        if (!isSafe(request) && !csrfTokenMatches(request)) {
            log.debug("rejected a cookie-authenticated {} without a matching CSRF token", request.getMethod());
            return;
        }

        authenticate(principal, true);
    }

    /**
     * A signed token cannot be unsigned, so logout and reuse-detection revocation only bite if the
     * session behind the token is checked. One indexed lookup per request; machine tokens carry no
     * session and skip it entirely.
     */
    private boolean sessionIsLive(TokenPrincipal principal) {
        return principal.sessionId() == null || authentication.isSessionLive(principal.sessionId());
    }

    private static boolean isSafe(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod());
    }

    /**
     * Any cookie under the name, not the first one found.
     *
     * <p>A browser can hold two {@code greenv_csrf} cookies at once — one host-only, one on the
     * registrable domain — and it sends both. Comparing only the first turned a harmless
     * duplicate into a 401 on every write, with the page echoing one value and this comparing
     * the other. The question the check asks is whether the caller could read the cookie, and
     * one match answers it.
     */
    private static boolean csrfTokenMatches(HttpServletRequest request) {
        String header = request.getHeader(AuthCookies.CSRF_HEADER);
        if (header == null || header.isBlank()) {
            return false;
        }
        return AuthCookies.readAll(request, AuthCookies.CSRF).stream()
                .anyMatch(cookie -> TokenDigest.digestsMatch(header, cookie));
    }

    private static void authenticate(TokenPrincipal principal, boolean fromCookie) {
        var authenticated = new AuthenticatedPrincipal(
                principal.subject(),
                principal.displayName(),
                principal.role(),
                principal.sessionId(),
                principal.machineClient(),
                fromCookie);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        authenticated,
                        null,
                        List.of(new SimpleGrantedAuthority(principal.role().authority()))));
    }

    private static Optional<String> headerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(authorization.substring(BEARER_PREFIX.length()));
    }
}
