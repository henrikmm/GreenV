package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;

public interface SegmentMessageCodec {

    String encode(SegmentExtractionRequest request);

    SegmentExtractionRequest decode(String message);
}
