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
 * The Flutter web capture build runs on an origin of its own, so its uploads are cross-origin. A
 * browser sends a preflight before any request that carries {@code X-Idempotency-Key} and friends,
 * and that preflight arrives without the Bearer token: it has to pass on its own.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.security.allowed-origins=http://localhost:5173"
        })
class ApiCorsIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String TEST_ID = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:cors-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void answersTheSegmentUploadPreflightWithoutAToken() throws Exception {
        HttpResponse<String> response = preflight(ALLOWED_ORIGIN, "PUT", "x-idempotency-key,x-content-sha256,x-captured-at,x-duration-millis,authorization,content-type");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("access-control-allow-origin")).contains(ALLOWED_ORIGIN);
        assertThat(response.headers().firstValue("access-control-allow-headers").orElse("").toLowerCase())
                .contains("x-idempotency-key")
                .contains("x-content-sha256")
                .contains("x-captured-at")
                .contains("x-duration-millis")
                .contains("authorization");
    }

    @Test
    void refusesAnOriginThatIsNotConfigured() throws Exception {
        HttpResponse<String> response = preflight("https://attacker.example", "PUT", "authorization");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String origin, String method, String headers) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port
                                + "/v2/capture-sessions/" + UUID.randomUUID() + "/segments/0/video"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
