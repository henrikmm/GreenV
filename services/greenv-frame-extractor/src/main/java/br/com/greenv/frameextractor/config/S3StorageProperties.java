package br.com.greenv.frameextractor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.storage.s3")
public record S3StorageProperties(
        String bucket,
        String region,
        String endpoint,
        boolean pathStyleAccess,
        String accessKey,
        String secretKey) {
}
