package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.SqsQueueProperties;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.SegmentMessageSerializer;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "sqs")
public class SqsSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final SqsClient sqsClient;
    private final SegmentMessageSerializer messageSerializer;
    private final SqsQueueProperties properties;

    public SqsSegmentWorkQueueAdapter(
            SqsClient sqsClient,
            SegmentMessageSerializer messageSerializer,
            SqsQueueProperties properties) {
        this.sqsClient = sqsClient;
        this.messageSerializer = messageSerializer;
        this.properties = properties;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            SendMessageRequest.Builder builder = SendMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .messageBody(messageSerializer.serialize(request));
            if (properties.queueUrl().endsWith(".fifo")) {
                builder.messageGroupId(valueOrDefault(properties.messageGroupId(), "segment-extraction"))
                        .messageDeduplicationId(request.idempotencyKey());
            }
            sqsClient.sendMessage(builder.build());
        } catch (SdkException exception) {
            throw unavailable(exception);
        }
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static ApplicationException unavailable(Exception exception) {
        return new ApplicationException(
                FailureKind.DEPENDENCY_UNAVAILABLE,
                "segment_queue_unavailable",
                "could not queue segment extraction: " + exception.getMessage());
    }
}
