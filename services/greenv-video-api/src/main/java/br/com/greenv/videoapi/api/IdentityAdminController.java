package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.AuthRole;
import br.com.greenv.videoapi.domain.AuthUserDocument;
import br.com.greenv.videoapi.port.IdentityAdminUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creates identities. Reachable only with the shared operational token, which
 * {@code BearerTokenAuthenticationFilter} grants {@code ROLE_PROVISIONER} - so no signed-in person,
 * however privileged, can mint a user or a machine client. That rule is enforced in
 * {@code ApiSecurityConfiguration}, not here.
 */
@RestController
@RequestMapping("/v2/identity")
public class IdentityAdminController {

    private final IdentityAdminUseCase identities;

    public IdentityAdminController(IdentityAdminUseCase identities) {
        this.identities = identities;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    AuthenticatedUserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        AuthUserDocument user = identities.createUser(
                request.email(), request.password(), request.displayName(), role(request.role()));
        return AuthenticatedUserResponse.from(user, null);
    }

    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    ClientRegistrationResponse createClient(@Valid @RequestBody CreateClientRequest request) {
        var registration = identities.createClient(request.displayName(), role(request.role()));
        return new ClientRegistrationResponse(
                registration.client().clientId(),
                // The only time this is readable. The store keeps a BCrypt digest and cannot
                // reproduce it, so a lost secret means issuing a new client.
                registration.secret(),
                registration.client().displayName(),
                registration.client().role().name(),
                registration.client().createdAt());
    }

    private static AuthRole role(String requested) {
        return requested == null || requested.isBlank() ? AuthRole.OPERATOR : AuthRole.of(requested);
    }
}
