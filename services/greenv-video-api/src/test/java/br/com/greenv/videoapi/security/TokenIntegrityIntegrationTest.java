package br.com.greenv.videoapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three properties the design rests on: a token cannot be altered, cannot be minted by anyone
 * else, and cannot be borrowed from another issuer or audience.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "greenv.security.api-token=greenv-test-only-bearer-token-000000000000",
            "greenv.auth.ephemeral-key=true",
            "greenv.auth.issuer=https://greenv.test",
            "greenv.auth.audience=greenv-video-api"
        })
class TokenIntegrityIntegrationTest {

    private static final String STATIC_TOKEN = "greenv-test-only-bearer-token-000000000000";
    private static final String TEST_ID = UUID.randomUUID().toString();
    private static final String PASSWORD = "uma-senha-bem-longa-123";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:integrity-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @LocalServerPort
    int port;

    /**
     * The RS256 signature is a hash over the header and payload. Flipping one character of the
     * payload breaks it - this is the "was the token altered" check, and it is the standard's,
     * not one layered on top.
     */
    @Test
    void rejectsATokenWhosePayloadWasAltered() throws Exception {
        String token = issuedToken();
        String[] parts = token.split("\\.");

        String payload = new String(
                Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        // Same length, so only the signature can tell the difference.
        String altered = payload.replace("ROLE_OPERATOR", "ROLE_OPERATOX");
        String tampered = parts[0]
                + "."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(altered.getBytes(StandardCharsets.UTF_8))
                + "."
                + parts[2];

        assertThat(callWith(tampered).statusCode()).isEqualTo(401);
        // The untouched token still works, so the rejection is about the edit and nothing else.
        assertThat(callWith(token).statusCode()).isEqualTo(404);
    }

    /** A perfectly well-formed token signed by somebody else's key. This is the "who issued it" check. */
    @Test
    void rejectsATokenSignedByAnotherKey() throws Exception {
        RSAKey foreignKey = new RSAKeyGenerator(2048).keyID("foreign").generate();

        String forged = sign(
                foreignKey,
                new JWTClaimsSet.Builder()
                        .issuer("https://greenv.test")
                        .audience("greenv-video-api")
                        .subject(UUID.randomUUID().toString())
                        .claim("use", "access")
                        .claim("rol", "ROLE_ADMIN")
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                        .build());

        assertThat(callWith(forged).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsATokenMintedForAnotherIssuerOrAudience() throws Exception {
        RSAKey ourKeyIsUnknownHere = new RSAKeyGenerator(2048).keyID("other").generate();

        String wrongIssuer = sign(
                ourKeyIsUnknownHere,
                claims().issuer("https://impostor.test").build());
        String wrongAudience = sign(
                ourKeyIsUnknownHere, claims().audience("some-other-api").build());

        assertThat(callWith(wrongIssuer).statusCode()).isEqualTo(401);
        assertThat(callWith(wrongAudience).statusCode()).isEqualTo(401);
    }

    /** The published key must be the public half and nothing more. */
    @Test
    void publishesOnlyThePublicKeyAndMatchesTheKidItSigns() throws Exception {
        HttpResponse<String> jwks = send(HttpRequest.newBuilder(uri("/.well-known/jwks.json")).GET());

        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(jwks.body())
                .contains("\"kty\":\"RSA\"")
                .contains("\"use\":\"sig\"")
                .contains("\"alg\":\"RS256\"")
                // Private RSA parameters must never appear.
                .doesNotContain("\"d\":")
                .doesNotContain("\"p\":")
                .doesNotContain("\"q\":");

        var mapper = JsonMapper.builder().build();
        String publishedKid =
                mapper.readTree(jwks.body()).path("keys").get(0).path("kid").asString();

        String header = new String(
                Base64.getUrlDecoder().decode(issuedToken().split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"kid\":\"" + publishedKid + "\"").contains("at+jwt");
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer("https://greenv.test")
                .audience("greenv-video-api")
                .subject(UUID.randomUUID().toString())
                .claim("use", "access")
                .claim("rol", "ROLE_OPERATOR")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)));
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(key.getKeyID())
                        .type(new JOSEObjectType("at+jwt"))
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        return jwt.serialize();
    }

    /** Logs a user in through the OAuth2 endpoint, where the token is readable. */
    private String issuedToken() throws Exception {
        String email = "operador-" + UUID.randomUUID() + "@motiva.com.br";
        send(HttpRequest.newBuilder(uri("/v2/identity/users"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\""
                        + PASSWORD + "\",\"displayName\":\"Operador\",\"role\":\"OPERATOR\"}")));

        HttpResponse<String> token = send(HttpRequest.newBuilder(uri("/v2/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=password&username=" + email + "&password=" + PASSWORD)));

        return JsonMapper.builder()
                .build()
                .readTree(token.body())
                .path("access_token")
                .asString();
    }

    private HttpResponse<String> callWith(String token) throws Exception {
        return send(HttpRequest.newBuilder(uri("/v2/capture-sessions/" + UUID.randomUUID()))
                .header("Authorization", "Bearer " + token)
                .GET());
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
