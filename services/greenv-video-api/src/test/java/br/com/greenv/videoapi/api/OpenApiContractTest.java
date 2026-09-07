package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps {@code openapi.yaml} honest.
 *
 * <p>Documentation that is written once and then drifts is worse than none, because a reader
 * trusts it. This asks Spring for the routes it actually serves and compares both directions: no
 * endpoint may go undocumented, and nothing may be documented that does not exist.
 */
@SpringBootTest(properties = "greenv.security.api-token=greenv-test-only-bearer-token-000000000000")
class OpenApiContractTest {

    private static final String TEST_ID = UUID.randomUUID().toString();

    /** Served by the actuator's own handler mapping, so it never appears in the scan below. */
    private static final Set<String> NOT_MAPPED_BY_CONTROLLERS = Set.of("/actuator/health");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:h2:mem:openapi-" + TEST_ID + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        registry.add("spring.rabbitmq.dynamic", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    // Qualified by name: the actuator registers a second RequestMappingHandlerMapping of its own,
    // and this test is about the controllers, not the management endpoints.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /**
     * Guards the two drift checks below. Both use {@code allSatisfy}, which passes on an empty set
     * - so if the scan ever stopped finding routes they would go green while checking nothing.
     */
    @Test
    void findsTheRoutesItIsSupposedToCompare() {
        assertThat(servedRoutes())
                .hasSizeGreaterThan(15)
                .contains(
                        "POST /v2/auth/login",
                        "POST /v2/oauth/token",
                        "GET /.well-known/jwks.json",
                        "POST /v2/identity/users",
                        "PUT /v2/capture-sessions/{sessionId}/segments/{segmentIndex}/video");
    }

    @Test
    void everyEndpointIsDocumented() throws Exception {
        Set<String> served = servedRoutes();
        Set<String> documented = documentedRoutes();

        assertThat(served)
                .as("routes the API serves but openapi.yaml does not describe")
                .allSatisfy(route -> assertThat(documented).contains(route));
    }

    @Test
    void nothingIsDocumentedThatDoesNotExist() throws Exception {
        Set<String> served = servedRoutes();
        Set<String> documented = new TreeSet<>(documentedRoutes());
        documented.removeIf(route ->
                NOT_MAPPED_BY_CONTROLLERS.stream().anyMatch(path -> route.endsWith(" " + path)));

        assertThat(documented)
                .as("routes openapi.yaml describes but the API does not serve")
                .allSatisfy(route -> assertThat(served).contains(route));
    }

    /** Every route declared by a controller in this application, as {@code "METHOD /path"}. */
    private Set<String> servedRoutes() {
        Set<String> routes = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().equals("br.com.greenv.videoapi.api")) {
                return;
            }
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                return;
            }
            patterns.getPatternValues().forEach(path -> info.getMethodsCondition()
                    .getMethods()
                    .forEach(method -> routes.add(method.name() + " " + path)));
        });
        return routes;
    }

    @SuppressWarnings("unchecked")
    private Set<String> documentedRoutes() throws Exception {
        // Read from the project root rather than the classpath: openapi.yaml is a published
        // artifact of the service, not a resource it loads.
        Path document = Path.of("openapi.yaml");
        assertThat(document).as("openapi.yaml must sit beside build.gradle").exists();

        Map<String, Object> spec;
        try (var reader = Files.newBufferedReader(document)) {
            spec = new Yaml().load(reader);
        }

        Set<String> routes = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths"))
                .forEach((path, operations) -> operations.keySet().stream()
                        .filter(key -> !key.equals("parameters"))
                        .forEach(method -> routes.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path)));
        return routes;
    }
}
