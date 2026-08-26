package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import java.time.Instant;

/** Control-plane segment state. Implementations may use any transactional database. */
public interface CaptureSegmentStore {

    String state(SegmentExtractionRequest request);

    void markValidating(SegmentExtractionRequest request, Instant now);

    void markReady(SegmentExtractionRequest request, String manifestObjectKey, int frameCount, Instant now);

    void markError(
            SegmentExtractionRequest request,
            String errorCode,
            String errorMessage,
            Instant now);
}
