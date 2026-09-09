package br.com.greenv.videoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.capture-queue")
public record CaptureQueueProperties(
        String exchange,
        String queue,
        String routingKey,
        String measuredQueue,
        String measuredRoutingKey) {

    public CaptureQueueProperties {
        if (exchange == null || exchange.isBlank()
                || queue == null || queue.isBlank()
                || routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("capture queue names are required");
        }
        // The measurement side is optional: a deployment without worker 2 declares nothing and
        // listens to nothing, which is what every deployment did until it existed.
        if (measuredQueue != null && measuredQueue.isBlank()) {
            measuredQueue = null;
        }
        if (measuredRoutingKey != null && measuredRoutingKey.isBlank()) {
            measuredRoutingKey = null;
        }
    }

    public boolean listensForMeasurements() {
        return measuredQueue != null && measuredRoutingKey != null;
    }
}
