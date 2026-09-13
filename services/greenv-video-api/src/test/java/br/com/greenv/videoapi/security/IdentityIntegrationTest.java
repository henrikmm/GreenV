package br.com.greenv.videoapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The whole human login flow, end to end, over the wire. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.auth.ephemeral-key=true",
            "greenv.auth.issuer=https://greenv.test"
        })
class IdentityIntegrationTest {

    private static final String STATIC_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final String PASSWORD = "uma-senha-bem-longa-123";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:identity-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void signsAUserInAndOutThroughCookies() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        assertThat(createUser(email).statusCode()).isEqualTo(201);

        HttpResponse<String> login = login(email, PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains(email).doesNotContain("eyJ");

        List<String> cookies = login.headers().allValues("set-cookie");
        assertThat(cookieNamed(cookies, AuthCookies.ACCESS))
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax")
                .contains("Path=/")
                // __Host- is only honoured without a Domain attribute, so its absence is the point.
                .doesNotContain("Domain");
        assertThat(cookieNamed(cookies, AuthCookies.REFRESH)).contains("HttpOnly");
        assertThat(cookieNamed(cookies, AuthCookies.FINGERPRINT)).contains("HttpOnly");
        // The CSRF cookie is the one the page has to read, so it must NOT be HttpOnly.
        assertThat(cookieNamed(cookies, AuthCookies.CSRF)).doesNotContain("HttpOnly");
        // Unset by default, which is right when the dashboard and the API share a host.
        assertThat(cookieNamed(cookies, AuthCookies.CSRF)).doesNotContain("Domain");

        String jar = jar(cookies);
        HttpResponse<String> me = get("/v2/auth/me", jar);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(email);

        HttpResponse<String> loggedOut = post("/v2/auth/logout", "", jar, csrf(cookies));
        assertThat(loggedOut.statusCode()).isEqualTo(204);
        assertThat(get("/v2/auth/me", jar).statusCode()).isEqualTo(401);
    }

    @Test
    void refusesAWrongPasswordAndAnUnknownAccount() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        createUser(email);

        assertThat(login(email, "a-outra-senha-longa-9").statusCode()).isEqualTo(401);
        assertThat(login("nao-existe-" + UUID.randomUUID() + "@motiva.com.br", PASSWORD).statusCode())
                .isEqualTo(401);
    }

    /**
     * The one that matters: a refresh token that has already been exchanged can only be in
     * circulation because it was copied, so presenting it kills every session of that user -
     * including the successor the legitimate holder is using.
     */
    @Test
    void revokesTheWholeFamilyWhenARetiredRefreshTokenReappears() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        createUser(email);

        List<String> first = login(email, PASSWORD).headers().allValues("set-cookie");
        String firstJar = jar(first);

        HttpResponse<String> rotated = post("/v2/auth/refresh", "", firstJar, csrf(first));
        assertThat(rotated.statusCode()).isEqualTo(200);

        List<String> second = rotated.headers().allValues("set-cookie");
        String secondJar = jar(second);
        assertThat(get("/v2/auth/me", secondJar).statusCode()).isEqualTo(200);

        // Replay the retired refresh token.
        assertThat(post("/v2/auth/refresh", "", firstJar, csrf(first)).statusCode()).isEqualTo(401);

        // The honest holder loses the session too. There is no way to tell the two apart.
        assertThat(post("/v2/auth/refresh", "", secondJar, csrf(second)).statusCode()).isEqualTo(401);
    }

    @Test
    void refusesACookieSessionWithoutItsFingerprint() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        createUser(email);

        List<String> cookies = login(email, PASSWORD).headers().allValues("set-cookie");
        String withoutFingerprint = jar(cookies.stream()
                .filter(cookie -> !cookie.startsWith(AuthCookies.FINGERPRINT + "="))
                .toList());

        // The access token itself is untouched and perfectly valid - it is the binding that fails.
        assertThat(get("/v2/auth/me", withoutFingerprint).statusCode()).isEqualTo(401);
    }

    @Test
    void refusesACookieAuthenticatedWriteWithoutTheCsrfHeader() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        createUser(email);

        List<String> cookies = login(email, PASSWORD).headers().allValues("set-cookie");
        String jar = jar(cookies);

        HttpResponse<String> withoutHeader = post("/v2/capture-sessions", "{}", jar, null);
        assertThat(withoutHeader.statusCode()).isEqualTo(401);

        HttpResponse<String> wrongValue = post("/v2/capture-sessions", "{}", jar, "not-the-right-value");
        assertThat(wrongValue.statusCode()).isEqualTo(401);
    }

    /** Creating identities is the shared operational token's job, and nobody else's. */
    @Test
    void refusesToCreateAUserWithoutTheProvisionerToken() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        createUser(email);

        List<String> cookies = login(email, PASSWORD).headers().allValues("set-cookie");
        HttpResponse<String> attempt = post(
                "/v2/identity/users",
                userBody("outro-" + UUID.randomUUID() + "@motiva.com.br"),
                jar(cookies),
                csrf(cookies));

        assertThat(attempt.statusCode()).as("body=%s", attempt.body()).isEqualTo(403);

        HttpResponse<String> ordinary = post("/v2/capture-sessions", "{}", jar(cookies), csrf(cookies));
        assertThat(ordinary.statusCode()).as("ordinary body=%s", ordinary.body()).isNotEqualTo(401);
    }

    private HttpResponse<String> createUser(String email) throws Exception {
        return send(HttpRequest.newBuilder(uri("/v2/identity/users"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(userBody(email))));
    }

    private static String userBody(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                + "\",\"displayName\":\"Operador\",\"role\":\"OPERATOR\"}";
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return send(HttpRequest.newBuilder(uri("/v2/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")));
    }

    private HttpResponse<String> get(String path, String jar) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Cookie", jar).GET());
    }

    private HttpResponse<String> post(String path, String body, String jar, String csrfToken)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Cookie", jar)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrfToken != null) {
            request.header(AuthCookies.CSRF_HEADER, csrfToken);
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

    /** Folds Set-Cookie headers into the name=value pairs a Cookie header carries. */
    private static String jar(List<String> setCookies) {
        return setCookies.stream()
                .map(cookie -> cookie.split(";", 2)[0])
                .filter(pair -> !pair.endsWith("="))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private static String csrf(List<String> setCookies) {
        return value(setCookies, AuthCookies.CSRF);
    }

    private static String value(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .map(cookie -> cookie.split(";", 2)[0].substring(name.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " cookie was set"));
    }

    private static String cookieNamed(List<String> setCookies, String name) {
        Optional<String> cookie =
                setCookies.stream().filter(candidate -> candidate.startsWith(name + "=")).findFirst();
        assertThat(cookie).as("Set-Cookie for %s", name).isPresent();
        return cookie.orElseThrow();
    }
}
