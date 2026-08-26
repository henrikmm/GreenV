package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.FrameExtractionRequest;

/** Queue boundary for the deprecated whole-video workflow. */
public interface FrameWorkQueue {

    void publish(FrameExtractionRequest request);
}
