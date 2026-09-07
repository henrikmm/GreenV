package br.com.greenv.videoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.queue.azure-service-bus")
public record AzureServiceBusProperties(
        String queue,
        String connectionString,
        String fullyQualifiedNamespace) {
}
