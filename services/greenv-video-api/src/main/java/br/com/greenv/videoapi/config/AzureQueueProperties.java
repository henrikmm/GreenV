package br.com.greenv.videoapi.config;

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
        // Where worker 2 announces a measurement. Empty leaves this service deaf to measurements,
        // exactly as an empty greenv.capture-queue.measured-queue does on the RabbitMQ path. A
        // separate name rather than that one reused: Azure Queue has no exchange to fan a routing
        // key out with, and an Azure queue name is 3 to 63 lower-case alphanumerics and dashes, so
        // greenv.segment.measured.v1 is not a name it will accept.
        String measuredQueue) {
}
