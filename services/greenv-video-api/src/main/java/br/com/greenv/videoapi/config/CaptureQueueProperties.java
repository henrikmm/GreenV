package br.com.greenv.videoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.capture-queue")
public record CaptureQueueProperties(String exchange, String queue, String routingKey) {

    public CaptureQueueProperties {
        if (exchange == null || exchange.isBlank()
                || queue == null || queue.isBlank()
                || routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("capture queue names are required");
        }
    }
}
