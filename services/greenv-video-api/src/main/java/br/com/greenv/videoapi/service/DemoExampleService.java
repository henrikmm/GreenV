package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoRun;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.DemoExampleUseCase;
import br.com.greenv.videoapi.port.ObjectUrlSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The recorded examples, read from {@code demo/examples/} in the demo's bucket.
 *
 * <p>Someone else uploads them; this only reads. {@code index.json} names them, and each one keeps
 * a {@code verge.infer-manifest/0.1.0} manifest, its artifacts and its frames
 * ({@code frames/frame-0001.jpg} onwards, as many as the index says) under its own directory.
 * The index and each manifest are kept for a minute, because the site asks on every page load and
 * a bucket read costs a round trip; a missing index is not kept, so examples uploaded mid-demo
 * appear on the next request.
 */
@Service
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoExampleService implements DemoExampleUseCase {

    private static final Logger log = LoggerFactory.getLogger(DemoExampleService.class);

    public static final String PREFIX = "demo/examples/";
    static final String INDEX_KEY = PREFIX + "index.json";
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private static final Pattern ID_PATTERN = Pattern.compile("^[0-9]{2}-[a-z0-9-]+$");
    /** Far above an index of a few examples or one manifest with three artifacts. */
    private static final long MAX_JSON_BYTES = 1024L * 1024;
    /** Far above the 112 the demo's GPU takes; bounds what one request signs. */
    private static final int MAX_FRAMES = 1000;

    private final CaptureObjectStorage storage;
    private final ObjectUrlSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DemoProperties properties;
    private final Map<String, Cached> manifests = new ConcurrentHashMap<>();
    private volatile Cached index;

    public DemoExampleService(
            CaptureObjectStorage storage,
            ObjectUrlSigner signer,
            ObjectMapper objectMapper,
            Clock clock,
            DemoProperties properties) {
        this.storage = storage;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    /** Where an example's objects sit; nothing of it is signed outside this. */
    public static String prefix(String exampleId) {
        return PREFIX + exampleId + "/";
    }

    @Override
    public List<JsonNode> examples() {
        JsonNode list = index().path("examples");
        if (!list.isArray()) {
            return List.of();
        }
        List<JsonNode> entries = new ArrayList<>();
        list.forEach(entries::add);
        return List.copyOf(entries);
    }

    @Override
    public DemoRun run(String exampleId) {
        JsonNode entry = entry(exampleId);
        String prefix = prefix(exampleId);
        int count = Math.max(0, Math.min(entry.path("frames").asInt(0), MAX_FRAMES));
        List<String> frames = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            frames.add(signer.sign(prefix + "frames/frame-%04d.jpg".formatted(index), properties.signedUrlTtl()));
        }
        JsonNode manifest = manifest(exampleId);
        JsonNode signed = manifest == null
                ? null
                : DemoManifests.signArtifacts(manifest, prefix, signer, properties.signedUrlTtl());
        JsonNode gpu = entry.path("gpu");
        JsonNode gpuSeconds = entry.path("gpuSeconds");
        return new DemoRun(
                frames,
                signed,
                gpu.isString() ? gpu.asString() : null,
                gpuSeconds.isNumber() ? gpuSeconds.asDouble() : null);
    }

    private JsonNode entry(String exampleId) {
        if (exampleId != null && ID_PATTERN.matcher(exampleId).matches()) {
            for (JsonNode entry : examples()) {
                if (exampleId.equals(entry.path("id").asString(""))) {
                    return entry;
                }
            }
        }
        throw new ApplicationException(FailureKind.NOT_FOUND, "demo_example_not_found", "no such example");
    }

    private JsonNode index() {
        Instant now = clock.instant();
        Cached cached = index;
        if (cached != null && now.isBefore(cached.until())) {
            return cached.json();
        }
        JsonNode read = readJson(INDEX_KEY);
        if (read == null) {
            return objectMapper.createObjectNode();
        }
        index = new Cached(read, now.plus(CACHE_TTL));
        return read;
    }

    private JsonNode manifest(String exampleId) {
        Instant now = clock.instant();
        Cached cached = manifests.get(exampleId);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.json();
        }
        JsonNode read = readJson(prefix(exampleId) + "manifest.json");
        if (read != null) {
            manifests.put(exampleId, new Cached(read, now.plus(CACHE_TTL)));
        }
        return read;
    }

    /** The object as JSON; null when it does not exist or is not JSON, which is logged. */
    private JsonNode readJson(String key) {
        if (!storage.exists(key)) {
            return null;
        }
        byte[] bytes = storage.read(key, MAX_JSON_BYTES);
        try {
            return objectMapper.readTree(bytes);
        } catch (JacksonException exception) {
            log.warn("[demo] {} is not JSON and is ignored: {}", key, exception.getOriginalMessage());
            return null;
        }
    }

    private record Cached(JsonNode json, Instant until) {
    }
}
