package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.AuthSessionTokens;
import br.com.greenv.videoapi.port.AuthenticationUseCase;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import br.com.greenv.videoapi.port.SessionCookieWriter;
import br.com.greenv.videoapi.security.AuthCookies;
import br.com.greenv.videoapi.security.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The browser's door into the identity provider.
 *
 * <p>It never puts a token in a response body. The access token, the refresh token and the
 * fingerprint it is bound to all leave as {@code HttpOnly} cookies, so no script on the page - and
 * therefore no XSS - can read them. Native and machine clients use {@code /v2/oauth/token} instead.
 */
@RestController
@RequestMapping("/v2/auth")
public class AuthController {

    private final AuthenticationUseCase authentication;
    private final SessionCookieWriter cookies;

    public AuthController(AuthenticationUseCase authentication, SessionCookieWriter cookies) {
        this.authentication = authentication;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    ResponseEntity<AuthenticatedUserResponse> login(@Valid @RequestBody LoginRequest request) {
        return sessionResponse(authentication.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthenticatedUserResponse> refresh(HttpServletRequest request) {
        String refreshToken = AuthCookies.read(request, AuthCookies.REFRESH).orElse(null);
        return sessionResponse(authentication.refresh(refreshToken));
    }

    /**
     * Public on purpose: an expired access token must still be able to end its session and clear
     * its cookies. The refresh cookie is what identifies the session, and presenting it is the only
     * authority needed to end it.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        authentication.logout(AuthCookies.read(request, AuthCookies.REFRESH).orElse(null));

        var headers = new HttpHeaders();
        cookies.close().forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie));
        return ResponseEntity.noContent().headers(headers).build();
    }

    /**
     * How a single-page app learns it is signed in. The cookies are unreadable to JavaScript, so
     * asking the server is the only way.
     */
    @GetMapping("/me")
    AuthenticatedUserResponse me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // The static capture token authenticates a String principal, not a person, so this
        // argument resolves to null for it. Dereferencing gave a 500 where the honest answer is
        // that the caller holds a credential which identifies no user.
        if (principal == null) {
            throw new ApplicationException(
                    FailureKind.UNAUTHORIZED,
                    "no_authenticated_user",
                    "this credential identifies no user; sign in to read an identity");
        }
        return AuthenticatedUserResponse.from(authentication.requireUser(principal.userId()), null);
    }

    private ResponseEntity<AuthenticatedUserResponse> sessionResponse(AuthSessionTokens tokens) {
        var headers = new HttpHeaders();
        cookies.open(tokens).forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie));

        return ResponseEntity.ok()
                .headers(headers)
                // Credentials must never sit in a shared cache or a browser's back-forward store.
                .cacheControl(CacheControl.noStore())
                .body(AuthenticatedUserResponse.from(tokens.user(), tokens.accessToken().expiresAt()));
    }
}
