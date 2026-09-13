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

    /**
     * The duplicate this fix created, and the two things that had to answer for it.
     *
     * <p>Cookies are keyed by name, domain and path, so widening greenv_csrf did not replace the
     * host-only one already in every browser that had signed in — it added a second cookie with
     * the same name, and the browser sends both. The page echoed one, the server compared the
     * other, and every write kept answering 401 with the fix already deployed.
     */
    @Test
    void acceptsTheHeaderWhenTwoCookiesOfTheSameNameArrive() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        assertThat(createUser(email).statusCode()).isEqualTo(201);

        List<String> cookies = login(email).headers().allValues("set-cookie");
        String meu = value(cookieNamed(cookies, AuthCookies.CSRF));

        // A jar holding a stale twin first, exactly as a browser would send it.
        String jar = "greenv_csrf=velho-de-antes-do-dominio; " + jar(cookies);

        HttpResponse<String> escrita = client.send(
                HttpRequest.newBuilder(URI.create(url("/v2/service-orders")))
                        .header("cookie", jar)
                        .header("content-type", "application/json")
                        .header(AuthCookies.CSRF_HEADER, meu)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // 400 and not 401: the body is rubbish on purpose, and rubbish is what an authenticated
        // caller gets told. 401 would mean the stale twin still decided the answer.
        assertThat(escrita.statusCode()).isNotEqualTo(401);
        assertThat(escrita.statusCode()).isBetween(400, 422);
    }

    /** And the twin is expired on the way in, so it does not outlive the login that replaced it. */
    @Test
    void expiresTheHostOnlyTwinOnLogin() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        assertThat(createUser(email).statusCode()).isEqualTo(201);

        List<String> csrfCookies = login(email).headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith(AuthCookies.CSRF + "="))
                .toList();

        assertThat(csrfCookies).hasSize(2);
        assertThat(csrfCookies).anySatisfy(cookie ->
                assertThat(cookie).contains("Domain=matomomitsu.com").doesNotContain("Max-Age=0"));
        assertThat(csrfCookies).anySatisfy(cookie ->
                assertThat(cookie).doesNotContain("Domain").contains("Max-Age=0"));
    }

    private static String value(String setCookie) {
        String head = setCookie.substring(setCookie.indexOf('=') + 1);
        int end = head.indexOf(';');
        return end < 0 ? head : head.substring(0, end);
    }

    private static String jar(List<String> cookies) {
        return cookies.stream()
                .filter(cookie -> !cookie.contains("Max-Age=0"))
                .map(cookie -> cookie.substring(0, cookie.indexOf(';')))
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
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
