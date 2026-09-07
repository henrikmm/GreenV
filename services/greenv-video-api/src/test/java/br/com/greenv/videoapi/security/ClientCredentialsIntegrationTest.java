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
import tools.jackson.databind.json.JsonMapper;

/** The machine-to-machine grant: one four-hour token, no refresh chain, no cookies. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.auth.ephemeral-key=true",
            "greenv.auth.issuer=https://greenv.test"
        })
class ClientCredentialsIntegrationTest {

    private static final String STATIC_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:clientcreds-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void issuesAFourHourTokenAndNoRefreshToken() throws Exception {
        var client = registerClient();

        HttpResponse<String> token = token(
                "grant_type=client_credentials&client_id=" + client.id + "&client_secret=" + client.secret);

        assertThat(token.statusCode()).isEqualTo(200);
        var body = JsonMapper.builder().build().readTree(token.body());
        assertThat(body.path("token_type").asString()).isEqualTo("Bearer");
        assertThat(body.path("expires_in").asLong()).isBetween(14000L, 14400L);
        // A machine has no cookie jar and nothing to rotate; the four hours are the whole control.
        assertThat(body.path("refresh_token").isMissingNode()).isTrue();
        assertThat(token.headers().firstValue("cache-control").orElse("")).contains("no-store");

        // The token is accepted: 404 means authentication passed and the session simply is not there.
        HttpResponse<String> call = send(HttpRequest.newBuilder(
                        uri("/v2/capture-sessions/" + UUID.randomUUID()))
                .header("Authorization", "Bearer " + body.path("access_token").asString())
                .GET());
        assertThat(call.statusCode()).isEqualTo(404);
    }

    @Test
    void refusesAWrongSecretWithTheOAuthErrorShape() throws Exception {
        var client = registerClient();

        HttpResponse<String> refused = token(
                "grant_type=client_credentials&client_id=" + client.id + "&client_secret=not-the-secret");

        assertThat(refused.statusCode()).isEqualTo(401);
        // RFC 6749 §5.2, not ProblemDetail: this is the endpoint an OAuth client library parses.
        assertThat(refused.body()).contains("\"error\":\"invalid_client\"");
    }

    @Test
    void refusesAnUnknownGrantType() throws Exception {
        HttpResponse<String> refused = token("grant_type=implicit");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("\"error\":\"unsupported_grant_type\"");
    }

    @Test
    void refusesToRegisterAClientWithoutTheProvisionerToken() throws Exception {
        HttpResponse<String> refused = send(HttpRequest.newBuilder(uri("/v2/identity/clients"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"Worker\"}")));

        assertThat(refused.statusCode()).isEqualTo(401);
    }

    private record RegisteredClient(String id, String secret) {}

    private RegisteredClient registerClient() throws Exception {
        HttpResponse<String> created = send(HttpRequest.newBuilder(uri("/v2/identity/clients"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"displayName\":\"Frame extractor\",\"role\":\"CAPTURE_CLIENT\"}")));

        assertThat(created.statusCode()).isEqualTo(201);
        var body = JsonMapper.builder().build().readTree(created.body());
        return new RegisteredClient(
                body.path("clientId").asString(), body.path("clientSecret").asString());
    }

    private HttpResponse<String> token(String form) throws Exception {
        return send(HttpRequest.newBuilder(uri("/v2/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
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
