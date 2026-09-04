package br.com.greenv.videoapi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    /**
     * ROLE_PROVISIONER is what gates {@code /v2/identity/**}: creating users and machine clients is
     * reserved for the shared operational credential, so no signed-in person - however privileged -
     * can mint identities. ROLE_CAPTURE_CLIENT is unchanged.
     */
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(
            new SimpleGrantedAuthority("ROLE_CAPTURE_CLIENT"),
            new SimpleGrantedAuthority("ROLE_PROVISIONER"));

    private final ApiTokenVerifier tokenVerifier;

    BearerTokenAuthenticationFilter(ApiTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = authorization.substring(BEARER_PREFIX.length());
            if (tokenVerifier.isValid(token)) {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        "capture-client", null, AUTHORITIES);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
