package br.com.greenv.videoapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The passwordless demonstration login, with {@code GREENV_DEMO_LOGIN_ENABLED=true}: it opens a
 * real session, that session reads, and every write it attempts is refused at the edge. The
 * disabled case is in {@link IdentityIntegrationTest}, which runs on the default configuration.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.auth.ephemeral-key=true",
            "greenv.auth.issuer=https://greenv.test",
            "greenv.demo-login.enabled=true"
        })
class DemoLoginIntegrationTest {

    private static final String STATIC_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final String PASSWORD = "uma-senha-bem-longa-123";
    private static final Pattern USER_ID = Pattern.compile("\"userId\"\\s*:\\s*\"([0-9a-f-]{36})\"");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:demo-login-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void opensAnOrdinaryCookieSessionWithTheDemoRole() throws Exception {
        HttpResponse<String> demo = post("/v2/auth/demo", "", null, null);
        assertThat(demo.statusCode()).as("body=%s", demo.body()).isEqualTo(200);
        assertThat(demo.body())
                .contains("\"role\":\"DEMO\"")
                .contains("demo@greenv.local")
                .doesNotContain("eyJ");

        List<String> cookies = demo.headers().allValues("set-cookie");
        assertThat(cookies)
                .anyMatch(cookie -> cookie.startsWith(AuthCookies.ACCESS + "=") && cookie.contains("HttpOnly"))
                .anyMatch(cookie -> cookie.startsWith(AuthCookies.REFRESH + "="))
                .anyMatch(cookie -> cookie.startsWith(AuthCookies.FINGERPRINT + "="))
                .anyMatch(cookie -> cookie.startsWith(AuthCookies.CSRF + "="));

        HttpResponse<String> me = get("/v2/auth/me", jar(cookies));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"role\":\"DEMO\"");
    }

    @Test
    void reusesOneAccountAcrossVisitors() throws Exception {
        String first = userId(post("/v2/auth/demo", "", null, null).body());
        String second = userId(post("/v2/auth/demo", "", null, null).body());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void readsWhatAnOperatorReads() throws Exception {
        List<String> cookies = demoCookies();
        String jar = jar(cookies);

        assertThat(get("/v2/capture-sessions", jar).statusCode()).isEqualTo(200);
        assertThat(get("/v2/service-orders", jar).statusCode()).isEqualTo(200);
        assertThat(get("/v2/teams", jar).statusCode()).isEqualTo(200);
        assertThat(get("/v2/measurements", jar).statusCode()).isEqualTo(200);
    }

    /** The writes that matter most: each of these can end in a GPU job that bills. */
    @Test
    void refusesEveryWriteWithItsOwnCode() throws Exception {
        List<String> cookies = demoCookies();
        String jar = jar(cookies);
        String csrf = csrf(cookies);
        String someOrder = "/v2/service-orders/" + UUID.randomUUID();

        assertDemoReadOnly(post("/v2/capture-sessions", "{}", jar, csrf));
        assertDemoReadOnly(post("/v2/service-orders", "{}", jar, csrf));
        assertDemoReadOnly(send(withSession(someOrder, jar, csrf)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"concluida\"}"))));
        assertDemoReadOnly(send(withSession(someOrder, jar, csrf).DELETE()));
        assertDemoReadOnly(send(withSession(
                        "/v2/capture-sessions/" + UUID.randomUUID() + "/segments/0/video", jar, csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("not a video"))));
        assertDemoReadOnly(post("/v2/identity/users", "{}", jar, csrf));
    }

    @Test
    void stillRefreshesAndEndsItsOwnSession() throws Exception {
        List<String> cookies = demoCookies();

        HttpResponse<String> refreshed = post("/v2/auth/refresh", "", jar(cookies), csrf(cookies));
        assertThat(refreshed.statusCode()).isEqualTo(200);

        List<String> rotated = refreshed.headers().allValues("set-cookie");
        HttpResponse<String> loggedOut = post("/v2/auth/logout", "", jar(rotated), csrf(rotated));
        assertThat(loggedOut.statusCode()).isEqualTo(204);
        assertThat(get("/v2/auth/me", jar(rotated)).statusCode()).isEqualTo(401);
    }

    /** The account has no password anyone knows, and the role is refused at /login regardless. */
    @Test
    void theDemoAccountCannotSignInWithAPassword() throws Exception {
        demoCookies();
        HttpResponse<String> attempt = send(HttpRequest.newBuilder(uri("/v2/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"demo@greenv.local\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(attempt.statusCode()).isEqualTo(401);
    }

    /** The read-only rule is keyed on the DEMO role and touches no one else. */
    @Test
    void leavesAnOperatorsWritesAlone() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        HttpResponse<String> created = send(HttpRequest.newBuilder(uri("/v2/identity/users"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\""
                        + PASSWORD + "\",\"displayName\":\"Operador\",\"role\":\"OPERATOR\"}")));
        assertThat(created.statusCode()).isEqualTo(201);

        HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/v2/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")));
        List<String> cookies = login.headers().allValues("set-cookie");

        HttpResponse<String> write = post("/v2/service-orders", "{}", jar(cookies), csrf(cookies));
        assertThat(write.statusCode()).as("body=%s", write.body()).isNotIn(401, 403);
        assertThat(write.body()).doesNotContain("demo_read_only");

        HttpResponse<String> staticTokenWrite = send(HttpRequest.newBuilder(uri("/v2/capture-sessions"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(staticTokenWrite.statusCode()).isNotIn(401, 403);
    }

    private static void assertDemoReadOnly(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("body=%s", response.body()).isEqualTo(403);
        assertThat(response.body()).contains("demo_read_only");
    }

    private List<String> demoCookies() throws Exception {
        HttpResponse<String> demo = post("/v2/auth/demo", "", null, null);
        assertThat(demo.statusCode()).isEqualTo(200);
        return demo.headers().allValues("set-cookie");
    }

    private HttpResponse<String> get(String path, String jar) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Cookie", jar).GET());
    }

    private HttpResponse<String> post(String path, String body, String jar, String csrfToken)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (jar != null) {
            request.header("Cookie", jar);
        }
        if (csrfToken != null) {
            request.header(AuthCookies.CSRF_HEADER, csrfToken);
        }
        return send(request);
    }

    private HttpRequest.Builder withSession(String path, String jar, String csrfToken) {
        return HttpRequest.newBuilder(uri(path))
                .header("Cookie", jar)
                .header("Content-Type", "application/json")
                .header(AuthCookies.CSRF_HEADER, csrfToken);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String userId(String body) {
        Matcher matcher = USER_ID.matcher(body);
        assertThat(matcher.find()).as("userId in %s", body).isTrue();
        return matcher.group(1);
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
        return setCookies.stream()
                .filter(cookie -> cookie.startsWith(AuthCookies.CSRF + "="))
                .map(cookie -> cookie.split(";", 2)[0].substring(AuthCookies.CSRF.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + AuthCookies.CSRF + " cookie was set"));
    }
}
