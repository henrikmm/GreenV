package br.com.greenv.videoapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The dashboard and the API do not have to be one host, and when they are not, one cookie has to
 * cross.
 *
 * <p>Served from greenv.matomomitsu.com against greenvapi.matomomitsu.com, every write answered
 * 401. The session cookies were fine — the browser sends those to the API by host — but the CSRF
 * cookie is the one the page has to read and echo, and a host-only cookie set by the API is
 * invisible to script on the dashboard. No header, no double-submit, no authentication. It reads
 * as a login that expired and is nothing of the kind, which is why this is pinned rather than
 * left to a comment.
 *
 * <p>What must not change with it: the session cookies keep the {@code __Host-} prefix, and that
 * prefix is void the moment a Domain appears. Widening them would let any sibling subdomain
 * shadow a session.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.auth.ephemeral-key=true",
            "greenv.auth.cookie.csrf-domain=matomomitsu.com"
        })
class CsrfCookieDomainIntegrationTest {

    private static final String STATIC_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String PASSWORD = "Uma-Senha-Bem-Longa-2026";
    private static final String TEST_ID = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:csrf-domain-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
        registry.add("greenv.places.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void widensOnlyTheCookieThePageHasToRead() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        assertThat(createUser(email).statusCode()).isEqualTo(201);

        List<String> cookies = login(email).headers().allValues("set-cookie");

        assertThat(cookieNamed(cookies, AuthCookies.CSRF))
                .contains("Domain=matomomitsu.com")
                .doesNotContain("HttpOnly");

        // The three that carry the session stay host-only, or the prefix means nothing.
        assertThat(cookieNamed(cookies, AuthCookies.ACCESS)).doesNotContain("Domain");
        assertThat(cookieNamed(cookies, AuthCookies.REFRESH)).doesNotContain("Domain");
        assertThat(cookieNamed(cookies, AuthCookies.FINGERPRINT)).doesNotContain("Domain");
    }

    private HttpResponse<String> createUser(String email) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url("/v2/identity/users")))
                        .header("authorization", "Bearer " + STATIC_TOKEN)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"%s\",\"password\":\"%s\",\"role\":\"OPERATOR\"}"
                                        .formatted(email, PASSWORD)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String email) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url("/v2/auth/login")))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String cookieNamed(List<String> cookies, String name) {
        return cookies.stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cookie named " + name + " in " + cookies));
    }
}
