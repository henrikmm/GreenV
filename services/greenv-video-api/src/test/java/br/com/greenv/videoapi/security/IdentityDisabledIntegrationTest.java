package br.com.greenv.videoapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The guard on the live capture pipeline. With no signing key and no explicit opt-in the identity
 * provider is off - and the deployment still starts, still passes its health probe, and still
 * accepts the static token on every capture route. Losing the JWT secret must never be able to stop
 * a phone from uploading.
 *
 * <p>Deliberately sets no {@code greenv.auth.*} property at all, which is also the state every
 * pre-existing test in this repository runs in.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class IdentityDisabledIntegrationTest {

    private static final String STATIC_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:disabled-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void startsAndKeepsServingTheCapturePipeline() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);

        // 404 rather than 401: the static token authenticated, the session simply does not exist.
        assertThat(get("/v2/capture-sessions/" + UUID.randomUUID(), STATIC_TOKEN).statusCode())
                .isEqualTo(404);
    }

    @Test
    void refusesToIssueTokensAndPublishesAnEmptyKeySet() throws Exception {
        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/v2/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"a@b.com\",\"password\":\"uma-senha-bem-longa-123\"}")));

        // 401 for a user that cannot exist; what matters is that nothing was signed.
        assertThat(login.statusCode()).isIn(401, 503);

        HttpResponse<String> jwks = get("/.well-known/jwks.json", null);
        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(jwks.body()).isEqualTo("{\"keys\":[]}");
    }

    /** With no key configured, a user can be created but no session can be opened for them. */
    @Test
    void refusesToSignAnythingEvenForARealUser() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        HttpResponse<String> created = send(HttpRequest.newBuilder(uri("/v2/identity/users"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email
                        + "\",\"password\":\"uma-senha-bem-longa-123\",\"displayName\":\"Operador\"}")));
        assertThat(created.statusCode()).isEqualTo(201);

        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/v2/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email
                        + "\",\"password\":\"uma-senha-bem-longa-123\"}")));

        assertThat(login.statusCode()).isEqualTo(503);
        assertThat(login.body()).contains("identity_provider_unconfigured");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
