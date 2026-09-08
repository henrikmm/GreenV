package br.com.greenv.frameextractor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where a finished segment is announced.
 *
 * The exchange is the capture exchange the extraction queue already uses; only the queue and the
 * routing key are new, so measurement is another consumer of one topology rather than a second
 * one to operate.
 */
@ConfigurationProperties("greenv.measurement")
public record MeasurementQueueProperties(boolean enabled, String queue, String routingKey) {

    public MeasurementQueueProperties {
        if (enabled && (queue == null || queue.isBlank() || routingKey == null || routingKey.isBlank())) {
            throw new IllegalArgumentException("measurement queue and routing key are required when measurement is enabled");
        }
    }
}
