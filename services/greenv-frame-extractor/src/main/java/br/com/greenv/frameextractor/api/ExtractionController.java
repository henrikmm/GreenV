package br.com.greenv.frameextractor.api;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.port.LegacyExtractionUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/extractions")
public class ExtractionController {

    private final LegacyExtractionUseCase extractionUseCase;

    public ExtractionController(LegacyExtractionUseCase extractionUseCase) {
        this.extractionUseCase = extractionUseCase;
    }

    @PostMapping
    ResponseEntity<ExtractionResponse> extract(@Valid @RequestBody FrameExtractionRequest request) {
        var result = extractionUseCase.execute(request);
        if (result.isReady()) {
            return ResponseEntity.ok(ExtractionResponse.ready(result.manifest()));
        }
        return ResponseEntity
                .status(result.shouldRetry() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK)
                .body(ExtractionResponse.failed(result.state(), result.errorCode(), result.errorMessage()));
    }
}
