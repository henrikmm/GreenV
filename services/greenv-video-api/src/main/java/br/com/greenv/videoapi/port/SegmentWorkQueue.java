package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.SegmentExtractionRequest;

/** Durable asynchronous delivery. Implementations own provider-specific routing and envelopes. */
public interface SegmentWorkQueue {

    void publish(SegmentExtractionRequest request);
}
