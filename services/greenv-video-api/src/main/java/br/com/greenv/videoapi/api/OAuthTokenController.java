package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.AuthSessionTokens;
import br.com.greenv.videoapi.domain.IssuedToken;
import br.com.greenv.videoapi.port.AuthenticationUseCase;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OAuth2 token endpoint, for clients that hold their credential rather than a cookie: the
 * Flutter capture app and machine clients.
 *
 * <p>Errors here follow RFC 6749 §5.2 rather than the repository's usual RFC 7807 ProblemDetail.
 * That is a deliberate exception: this is the one endpoint an off-the-shelf OAuth client library
 * will parse, and it expects {@code {"error": "..."}}.
 */
@RestController
public class OAuthTokenController {

    private static final String GRANT_PASSWORD = "password";
    private static final String GRANT_REFRESH = "refresh_token";
    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    private final AuthenticationUseCase authentication;

    public OAuthTokenController(AuthenticationUseCase authentication) {
        this.authentication = authentication;
    }

    @PostMapping(path = "/v2/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<TokenResponse> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "password", required = false) String password,
            @RequestParam(name = "refresh_token", required = false) String refreshToken,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret) {

        return switch (grantType) {
            // Resource owner password credentials. Discouraged by OAuth 2.1 for third-party
            // clients; used here only by our own first-party capture app, which is the context
            // where it is still the ordinary choice.
            case GRANT_PASSWORD -> session(authentication.login(username, password));
            case GRANT_REFRESH -> session(authentication.refresh(refreshToken));
            case GRANT_CLIENT_CREDENTIALS -> single(
                    authentication.authenticateClient(clientId, clientSecret));
            default -> throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "unsupported_grant_type", "unsupported grant_type");
        };
    }

    private static ResponseEntity<TokenResponse> session(AuthSessionTokens tokens) {
        return ok(TokenResponse.bearer(
                tokens.accessToken().value(),
                expiresIn(tokens.accessToken()),
                tokens.refreshToken()));
    }

    private static ResponseEntity<TokenResponse> single(IssuedToken token) {
        // No refresh token: a machine client re-runs the grant. Nothing to rotate, nothing to steal
        // that outlives the four hours.
        return ok(TokenResponse.bearer(token.value(), expiresIn(token), null));
    }

    private static ResponseEntity<TokenResponse> ok(TokenResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static long expiresIn(IssuedToken token) {
        return Math.max(0, Duration.between(Instant.now(), token.expiresAt()).toSeconds());
    }

    /**
     * RFC 6749 §5.2 error shape, scoped to this controller so the rest of the API keeps its
     * ProblemDetail responses.
     */
    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<Map<String, String>> handle(ApplicationException exception) {
        return switch (exception.kind()) {
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"greenv\"")
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "invalid_client"));
            case DEPENDENCY_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "temporarily_unavailable", "error_description", exception.code()));
            default -> ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("error", errorCode(exception)));
        };
    }

    private static String errorCode(ApplicationException exception) {
        return "unsupported_grant_type".equals(exception.code()) ? exception.code() : "invalid_request";
    }
}
