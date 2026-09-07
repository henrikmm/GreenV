package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.SegmentExtractionRequest;

public interface SegmentMessageSerializer {

    String serialize(SegmentExtractionRequest request);
}
