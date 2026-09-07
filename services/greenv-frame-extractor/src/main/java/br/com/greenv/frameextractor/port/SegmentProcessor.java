package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.domain.SegmentManifest;

/** Processing boundary used by extraction orchestration. */
public interface SegmentProcessor {

    SegmentManifest extract(SegmentExtractionRequest request);
}
