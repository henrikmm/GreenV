package br.com.greenv.frameextractor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.queue.azure-service-bus")
public record AzureServiceBusProperties(
        String queue,
        String connectionString,
        String fullyQualifiedNamespace,
        int maximumMessages,
        int receiveTimeoutSeconds) {
}
