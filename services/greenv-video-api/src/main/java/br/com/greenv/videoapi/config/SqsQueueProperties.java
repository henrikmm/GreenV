package br.com.greenv.videoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.queue.sqs")
public record SqsQueueProperties(
        String queueUrl,
        String region,
        String endpoint,
        String accessKey,
        String secretKey,
        String messageGroupId) {
}
