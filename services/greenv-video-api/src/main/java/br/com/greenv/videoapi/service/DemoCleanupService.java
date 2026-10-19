package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.DemoCaptureStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Forgets demo captures after {@code greenv.demo.retention-days}, three by default.
 *
 * <p>A demo capture is scratch with a visitor's face in it. Three days covers an evening's
 * comparisons and the morning after; nothing about it is worth keeping longer. The objects go
 * first and the row last, so a cleanup interrupted halfway is finished by the next one rather than
 * leaving files nothing points at.
 */
@Service
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DemoCleanupService.class);

    private final DemoCaptureStore store;
    private final CaptureObjectStorage storage;
    private final DemoProperties properties;
    private final Clock clock;

    public DemoCleanupService(
            DemoCaptureStore store,
            CaptureObjectStorage storage,
            DemoProperties properties,
            Clock clock) {
        this.store = store;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void removeExpiredCaptures() {
        Instant cutoff = clock.instant().minus(properties.retentionDays(), ChronoUnit.DAYS);
        for (DemoCapture capture : store.createdBefore(cutoff, 50)) {
            try {
                for (String key : objectKeys(capture)) {
                    storage.delete(key);
                }
                store.delete(capture.id());
                log.info("[demo] {} removed after {} days", capture.id(), properties.retentionDays());
            } catch (RuntimeException exception) {
                log.warn("[demo] {} could not be removed yet: {}", capture.id(), exception.getMessage());
            }
        }
    }

    /** Everything the capture is known to own: its video, its frames, its published artifacts. */
    static Set<String> objectKeys(DemoCapture capture) {
        String prefix = DemoCapture.prefix(capture.id());
        Set<String> keys = new LinkedHashSet<>();
        if (capture.video() != null) {
            keys.add(capture.video().objectKey());
        }
        keys.addAll(capture.frameKeys());
        if (capture.manifest() != null && capture.manifest().path("artifacts").isArray()) {
            for (JsonNode artifact : capture.manifest().path("artifacts")) {
                keys.add(artifact.path("object_key").asString(""));
            }
        }
        // Never anything outside the capture's own prefix, whatever a manifest claimed.
        keys.removeIf(key -> key == null || !key.startsWith(prefix) || key.contains(".."));
        return keys;
    }
}
