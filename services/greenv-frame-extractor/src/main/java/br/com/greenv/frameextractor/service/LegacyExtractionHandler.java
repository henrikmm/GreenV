package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ConditionalOnLocalPipeline;
import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.port.LegacyExtractionResult;
import br.com.greenv.frameextractor.port.LegacyExtractionUseCase;
import br.com.greenv.frameextractor.port.LegacyFrameProcessor;
import br.com.greenv.frameextractor.port.LegacyPipelineStore;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnLocalPipeline
public class LegacyExtractionHandler implements LegacyExtractionUseCase {

    private final LegacyFrameProcessor frameProcessor;
    private final LegacyPipelineStore pipelineStore;
    private final ExtractorProperties extractorProperties;
    private final Clock clock;

    public LegacyExtractionHandler(
            LegacyFrameProcessor frameProcessor,
            LegacyPipelineStore pipelineStore,
            ExtractorProperties extractorProperties,
            Clock clock) {
        this.frameProcessor = frameProcessor;
        this.pipelineStore = pipelineStore;
        this.extractorProperties = extractorProperties;
        this.clock = clock;
    }

    @Override
    public LegacyExtractionResult execute(FrameExtractionRequest request) {
        try {
            return LegacyExtractionResult.ready(frameProcessor.extract(request));
        } catch (ExtractionException exception) {
            boolean retry = exception.retryable()
                    && request.attempt() + 1 < extractorProperties.maxAttempts();
            String state = retry ? "queued" : "failed";
            pipelineStore.markError(
                    request.statusUri(),
                    state,
                    exception.code(),
                    exception.getMessage(),
                    clock.instant());
            return LegacyExtractionResult.failed(state, exception.code(), exception.getMessage());
        }
    }
}
