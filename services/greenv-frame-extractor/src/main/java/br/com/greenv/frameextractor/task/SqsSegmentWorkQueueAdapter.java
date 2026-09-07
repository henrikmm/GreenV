package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.SqsQueueProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import br.com.greenv.frameextractor.service.ExtractionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "sqs")
public class SqsSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final SqsClient sqsClient;
    private final SegmentMessageCodec messageCodec;
    private final SqsQueueProperties properties;

    public SqsSegmentWorkQueueAdapter(
            SqsClient sqsClient,
            SegmentMessageCodec messageCodec,
            SqsQueueProperties properties) {
        this.sqsClient = sqsClient;
        this.messageCodec = messageCodec;
        this.properties = properties;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            SendMessageRequest.Builder builder = SendMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .messageBody(messageCodec.encode(request));
            if (properties.queueUrl().endsWith(".fifo")) {
                builder.messageGroupId(valueOrDefault(properties.messageGroupId(), "segment-extraction"))
                        .messageDeduplicationId(request.idempotencyKey());
            }
            sqsClient.sendMessage(builder.build());
        } catch (SdkException exception) {
            throw publishFailed(exception);
        }
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static ExtractionException publishFailed(Exception exception) {
        return new ExtractionException(
                "segment_retry_publish_failed",
                "could not publish the next segment extraction attempt",
                true,
                exception);
    }
}
