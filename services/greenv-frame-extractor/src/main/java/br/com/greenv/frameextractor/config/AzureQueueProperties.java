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
        int maximumDequeueCount) {
}
