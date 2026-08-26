package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import br.com.greenv.frameextractor.port.SegmentProcessor;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class SegmentExtractionHandler implements SegmentExtractionUseCase {

    private final SegmentProcessor segmentProcessor;
    private final CaptureSegmentStore segmentStore;
    private final SegmentWorkQueue workQueue;
    private final ExtractorProperties extractorProperties;
    private final Clock clock;

    public SegmentExtractionHandler(
            SegmentProcessor segmentProcessor,
            CaptureSegmentStore segmentStore,
            SegmentWorkQueue workQueue,
            ExtractorProperties extractorProperties,
            Clock clock) {
        this.segmentProcessor = segmentProcessor;
        this.segmentStore = segmentStore;
        this.workQueue = workQueue;
        this.extractorProperties = extractorProperties;
        this.clock = clock;
    }

    @Override
    public void handle(SegmentExtractionRequest request) {
        try {
            if (!"ready".equals(segmentStore.state(request))) {
                segmentProcessor.extract(request);
            }
        } catch (ExtractionException exception) {
            boolean retry = exception.retryable()
                    && request.attempt() + 1 < extractorProperties.maxAttempts();
            if (retry) {
                workQueue.publish(request.nextAttempt());
            } else {
                segmentStore.markError(
                        request,
                        exception.code(),
                        exception.getMessage(),
                        clock.instant());
            }
        }
    }
}
