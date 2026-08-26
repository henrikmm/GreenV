package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.port.LegacyExtractionUseCase;
import br.com.greenv.frameextractor.port.LegacyTaskInbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "greenv.extractor.local-polling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LocalLegacyTaskPollerAdapter {

    private final LegacyTaskInbox taskInbox;
    private final LegacyExtractionUseCase extractionUseCase;

    public LocalLegacyTaskPollerAdapter(
            LegacyTaskInbox taskInbox,
            LegacyExtractionUseCase extractionUseCase) {
        this.taskInbox = taskInbox;
        this.extractionUseCase = extractionUseCase;
    }

    @Scheduled(fixedDelayString = "${greenv.extractor.poll-delay-ms:1000}")
    public void poll() {
        taskInbox.claim().ifPresent(this::process);
    }

    private void process(LegacyTaskInbox.ClaimedTask task) {
        var result = extractionUseCase.execute(task.request());
        if (result.isReady()) {
            taskInbox.complete(task);
        } else if (result.shouldRetry()) {
            taskInbox.retry(task);
        } else {
            taskInbox.fail(task);
        }
    }
}
