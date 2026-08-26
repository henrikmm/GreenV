package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;

/** Inbound operation for the deprecated whole-video workflow. */
public interface LegacyExtractionUseCase {

    LegacyExtractionResult execute(FrameExtractionRequest request);
}
