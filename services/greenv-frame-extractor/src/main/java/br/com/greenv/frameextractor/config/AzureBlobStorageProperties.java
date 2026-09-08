package br.com.greenv.frameextractor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.storage.azure-blob")
public record AzureBlobStorageProperties(
        String container,
        String connectionString,
        String endpoint,
        boolean createContainer) {
}
