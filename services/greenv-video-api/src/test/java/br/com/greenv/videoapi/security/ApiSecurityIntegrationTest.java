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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class ApiSecurityIntegrationTest {

    private static final String TEST_API_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:security-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void keepsHealthPublic() throws Exception {
        HttpResponse<String> response = send("/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsMissingAndInvalidBearerTokens() throws Exception {
        String target = "/v2/capture-sessions/" + UUID.randomUUID();

        HttpResponse<String> missing = send(target, null);
        HttpResponse<String> invalid = send(target, "wrong-token-with-at-least-32-characters");

        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(invalid.statusCode()).isEqualTo(401);
        assertThat(missing.headers().firstValue("content-type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(missing.headers().firstValue("www-authenticate")).contains("Bearer");
        assertThat(missing.body()).contains("\"title\":\"unauthorized\"");
    }

    @Test
    void acceptsTheConfiguredBearerToken() throws Exception {
        HttpResponse<String> response = send(
                "/v2/capture-sessions/" + UUID.randomUUID(), TEST_API_TOKEN);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
