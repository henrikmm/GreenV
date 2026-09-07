package br.com.greenv.videoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.queue.azure-queue")
public record AzureQueueProperties(
        String queue,
        String connectionString,
        String endpoint,
        boolean createQueue) {
}
