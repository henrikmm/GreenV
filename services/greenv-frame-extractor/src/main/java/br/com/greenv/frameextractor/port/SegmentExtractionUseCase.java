package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;

/** Inbound operation invoked by any queue or HTTP trigger adapter. */
public interface SegmentExtractionUseCase {

    void handle(SegmentExtractionRequest request);
}
