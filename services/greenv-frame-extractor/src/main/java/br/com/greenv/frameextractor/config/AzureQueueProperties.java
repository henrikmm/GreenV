package br.com.greenv.frameextractor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.queue.azure-queue")
public record AzureQueueProperties(
        String queue,
        String connectionString,
        String endpoint,
        boolean createQueue,
        int maximumMessages,
        int visibilityTimeoutSeconds,
        String poisonQueue,
        int maximumDequeueCount,
        // Where a finished segment is announced for measurement. A second name rather than a
        // routing key: Azure Queue has no exchange, so the one greenv.capture exchange the RabbitMQ
        // path fans out from becomes one queue per destination. It is also a different alphabet —
        // an Azure queue name is 3 to 63 lower-case alphanumerics and dashes, so
        // greenv.segment.measure.v1 cannot be reused.
        String measurementQueue) {
}
