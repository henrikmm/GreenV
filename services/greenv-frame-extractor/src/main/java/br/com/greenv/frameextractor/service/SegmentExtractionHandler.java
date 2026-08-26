package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class SegmentExtractionHandler {

    private final SegmentExtractionService extractionService;
    private final CaptureSegmentStore segmentStore;
    private final SegmentWorkQueue workQueue;
    private final ExtractorProperties properties;
    private final Clock clock;

    public SegmentExtractionHandler(
            SegmentExtractionService extractionService,
            CaptureSegmentStore segmentStore,
            SegmentWorkQueue workQueue,
            ExtractorProperties properties,
            Clock clock) {
        this.extractionService = extractionService;
        this.segmentStore = segmentStore;
        this.workQueue = workQueue;
        this.properties = properties;
        this.clock = clock;
    }

    public void handle(SegmentExtractionRequest request) {
        try {
            if (!"ready".equals(segmentStore.state(request))) {
                extractionService.extract(request);
            }
        } catch (ExtractionException exception) {
            boolean retry = exception.retryable() && request.attempt() + 1 < properties.maxAttempts();
            if (retry) {
                workQueue.publish(request.nextAttempt());
            } else {
                segmentStore.markError(request, exception, clock.instant());
            }
        }
    }
}
