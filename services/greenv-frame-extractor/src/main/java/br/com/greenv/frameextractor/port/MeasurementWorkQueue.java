package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.MeasurementRequest;

/**
 * Hands a finished segment to the measurement stage.
 *
 * Extraction does not identify grass, estimate height or call the depth model; it publishes here
 * and is done. Publishing must never fail an extraction that already succeeded — the frames are
 * in object storage either way, and a segment that can be measured later is worth more than one
 * re-extracted because its announcement did not send.
 */
public interface MeasurementWorkQueue {

    void publish(MeasurementRequest request);
}
