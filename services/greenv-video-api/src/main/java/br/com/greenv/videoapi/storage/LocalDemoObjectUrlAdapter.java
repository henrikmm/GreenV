package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.port.ObjectUrlSigner;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Links to {@code GET /v1/demo/captures/{id}/files/...} on this API, for local storage, which has
 * no URL of its own to sign. It is what lets the phone, the API and the site run together on one laptop.
 *
 * <p>Nothing is signed: the demo's objects are public by contract, and the route serves nothing
 * outside {@code demo/}. {@code ttl} is ignored for the same reason. The link is absolute because
 * the site lives on another origin; it is built from the request being answered, so it carries
 * whatever host the caller reached, and from {@code greenv.demo.public-base-url} outside one.
 */
@Component
@ConditionalOnExpression("${greenv.demo.enabled:false} and '${greenv.adapters.object-storage:local}' == 'local'")
public class LocalDemoObjectUrlAdapter implements ObjectUrlSigner {

    private final String fallbackBaseUrl;

    public LocalDemoObjectUrlAdapter(DemoProperties properties) {
        String base = properties.publicBaseUrl() == null ? "" : properties.publicBaseUrl().strip();
        this.fallbackBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String sign(String objectKey, Duration ttl) {
        String[] parts = CloudStorageSupport.requireObjectKey(objectKey).split("/");
        if (parts.length < 3 || !parts[0].equals("demo")) {
            throw new IllegalArgumentException("only demo/<capture>/... objects are served locally: " + objectKey);
        }
        String rest = Arrays.stream(parts, 2, parts.length)
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        return baseUrl() + "/v1/demo/captures/" + URLEncoder.encode(parts[1], StandardCharsets.UTF_8)
                + "/files/" + rest;
    }

    private String baseUrl() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return fallbackBaseUrl;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
