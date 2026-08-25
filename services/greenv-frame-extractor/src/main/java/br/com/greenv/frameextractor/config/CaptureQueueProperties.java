package br.com.greenv.frameextractor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.capture")
public record CaptureQueueProperties(String exchange, String queue, String routingKey) {

    public CaptureQueueProperties {
        if (exchange == null || exchange.isBlank()
                || queue == null || queue.isBlank()
                || routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("capture RabbitMQ names are required");
        }
    }
}
