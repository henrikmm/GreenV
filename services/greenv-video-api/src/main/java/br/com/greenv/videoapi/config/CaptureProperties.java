package br.com.greenv.videoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.capture")
public record CaptureProperties(
        int segmentSeconds,
        long maxSegmentBytes,
        long maxTelemetryBytes,
        String exchange,
        String queue,
        String routingKey) {

    public CaptureProperties {
        if (segmentSeconds <= 0 || maxSegmentBytes <= 0 || maxTelemetryBytes <= 0) {
            throw new IllegalArgumentException("capture limits must be positive");
        }
        if (exchange == null || exchange.isBlank()
                || queue == null || queue.isBlank()
                || routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("capture RabbitMQ names are required");
        }
    }
}
