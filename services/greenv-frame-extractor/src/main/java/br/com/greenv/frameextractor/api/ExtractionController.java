package br.com.greenv.frameextractor.api;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.service.ExtractionException;
import br.com.greenv.frameextractor.service.ExtractionService;
import br.com.greenv.frameextractor.port.LegacyPipelineStore;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/extractions")
public class ExtractionController {

    private final ExtractionService extractionService;
    private final LegacyPipelineStore store;
    private final Clock clock;

    public ExtractionController(ExtractionService extractionService, LegacyPipelineStore store, Clock clock) {
        this.extractionService = extractionService;
        this.store = store;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<ExtractionResponse> extract(@Valid @RequestBody FrameExtractionRequest request) {
        try {
            return ResponseEntity.ok(ExtractionResponse.ready(extractionService.extract(request)));
        } catch (ExtractionException exception) {
            String state = exception.retryable() ? "queued" : "failed";
            store.markError(request.statusUri(), state, exception, clock.instant());
            return ResponseEntity
                    .status(exception.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
                    .body(ExtractionResponse.failed(state, exception.code(), exception.getMessage()));
        }
    }
}
