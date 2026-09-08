package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.MeasurementRequest;
import br.com.greenv.frameextractor.port.MeasurementWorkQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * What runs when nothing measures.
 *
 * A deployment without the measurement worker must still extract frames, so the port always has
 * an implementation. Wiring an optional dependency unconditionally is what once made the deployed
 * worker fail to start and left every uploaded segment sitting in {@code queued}.
 */
@Component
@ConditionalOnProperty(name = "greenv.measurement.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledMeasurementWorkQueueAdapter implements MeasurementWorkQueue {

    @Override
    public void publish(MeasurementRequest request) {
        // Measurement is not deployed here.
    }
}
