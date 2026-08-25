package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExtractionServiceValidationTest {

    @Test
    void rejectsAnInvalidLocalQueueMessageBeforeAccessingStorage() {
        FrameExtractionRequest invalid = new FrameExtractionRequest(
                1,
                0,
                UUID.randomUUID(),
                "key",
                "file:///source",
                "file:///status",
                "file:///output",
                "not-a-sha-256",
                10,
                100,
                1024,
                Instant.parse("2026-08-16T12:00:00Z"));
        ExtractionService service = new ExtractionService(null, null, null, null, null, null);

        assertThatThrownBy(() -> service.extract(invalid))
                .isInstanceOfSatisfying(ExtractionException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo("invalid_extraction_request");
                    org.assertj.core.api.Assertions.assertThat(exception.retryable()).isFalse();
                });
    }
}

