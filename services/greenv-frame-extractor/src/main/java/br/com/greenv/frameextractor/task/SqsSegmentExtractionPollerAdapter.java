package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.SqsQueueProperties;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "sqs")
public class SqsSegmentExtractionPollerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqsSegmentExtractionPollerAdapter.class);

    private final SqsClient sqsClient;
    private final SegmentMessageCodec messageCodec;
    private final SegmentExtractionUseCase extractionUseCase;
    private final SqsQueueProperties properties;

    public SqsSegmentExtractionPollerAdapter(
            SqsClient sqsClient,
            SegmentMessageCodec messageCodec,
            SegmentExtractionUseCase extractionUseCase,
            SqsQueueProperties properties) {
        this.sqsClient = sqsClient;
        this.messageCodec = messageCodec;
        this.extractionUseCase = extractionUseCase;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${greenv.queue.poll-delay-ms:1000}")
    public void poll() {
        try {
            var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(bounded(properties.maximumMessages(), 1, 10, 1))
                    .waitTimeSeconds(bounded(properties.waitTimeSeconds(), 0, 20, 10))
                    .visibilityTimeout(bounded(properties.visibilityTimeoutSeconds(), 1, 43200, 300))
                    .build());
            response.messages().forEach(this::process);
        } catch (SdkException exception) {
            LOGGER.warn("Could not poll SQS segment queue: {}", exception.getMessage());
        }
    }

    void process(Message message) {
        try {
            extractionUseCase.handle(messageCodec.decode(message.body()));
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .receiptHandle(message.receiptHandle())
                    .build());
        } catch (RuntimeException exception) {
            LOGGER.warn("SQS segment message {} was not acknowledged: {}", message.messageId(), exception.getMessage());
        }
    }

    private static int bounded(int value, int minimum, int maximum, int defaultValue) {
        int selected = value == 0 ? defaultValue : value;
        return Math.max(minimum, Math.min(maximum, selected));
    }
}
