package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;

/** Durable redelivery for retryable extraction work. */
public interface SegmentWorkQueue {

    void publish(SegmentExtractionRequest request);
}
