package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.service.ExtractionException;
import br.com.greenv.frameextractor.service.ExtractionService;
import br.com.greenv.frameextractor.port.LegacyPipelineStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "greenv.extractor.local-polling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LocalTaskPoller {

    private final LocalTaskInbox inbox;
    private final ExtractionService extractionService;
    private final LegacyPipelineStore store;
    private final ExtractorProperties properties;
    private final Clock clock;

    public LocalTaskPoller(
            LocalTaskInbox inbox,
            ExtractionService extractionService,
            LegacyPipelineStore store,
            ExtractorProperties properties,
            Clock clock) {
        this.inbox = inbox;
        this.extractionService = extractionService;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${greenv.extractor.poll-delay-ms:1000}")
    public void poll() {
        inbox.claim().ifPresent(this::process);
    }

    private void process(LocalTaskInbox.ClaimedTask task) {
        try {
            extractionService.extract(task.request());
            inbox.complete(task);
        } catch (ExtractionException exception) {
            boolean retry = exception.retryable() && task.request().attempt() + 1 < properties.maxAttempts();
            store.markError(
                    task.request().statusUri(),
                    retry ? "queued" : "failed",
                    exception,
                    clock.instant());
            if (retry) {
                inbox.retry(task);
            } else {
                inbox.fail(task);
            }
        }
    }
}
