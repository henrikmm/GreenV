package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.domain.FrameManifest;

/** CPU processing boundary for the deprecated whole-video workflow. */
public interface LegacyFrameProcessor {

    FrameManifest extract(FrameExtractionRequest request);
}
