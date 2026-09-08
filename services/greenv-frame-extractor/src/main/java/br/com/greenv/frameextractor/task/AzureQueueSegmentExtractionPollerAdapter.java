package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.AzureQueueProperties;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import com.azure.core.exception.AzureException;
import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
public class AzureQueueSegmentExtractionPollerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureQueueSegmentExtractionPollerAdapter.class);

    private final QueueClient queueClient;
    private final QueueClient poisonQueueClient;
    private final SegmentMessageCodec messageCodec;
    private final SegmentExtractionUseCase extractionUseCase;
    private final AzureQueueProperties properties;

    public AzureQueueSegmentExtractionPollerAdapter(
            @Qualifier("azureSegmentQueueClient") QueueClient queueClient,
            @Qualifier("azureSegmentPoisonQueueClient") QueueClient poisonQueueClient,
            SegmentMessageCodec messageCodec,
            SegmentExtractionUseCase extractionUseCase,
            AzureQueueProperties properties) {
        this.queueClient = queueClient;
        this.poisonQueueClient = poisonQueueClient;
        this.messageCodec = messageCodec;
        this.extractionUseCase = extractionUseCase;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${greenv.queue.poll-delay-ms:1000}")
    public void poll() {
        try {
            queueClient.receiveMessages(
                            bounded(properties.maximumMessages(), 1, 32, 1),
                            Duration.ofSeconds(positive(properties.visibilityTimeoutSeconds(), 300)),
                            Duration.ofSeconds(10),
                            Context.NONE)
                    .forEach(this::process);
        } catch (AzureException exception) {
            LOGGER.warn("Could not poll Azure Queue segment queue: {}", exception.getMessage());
        }
    }

    void process(QueueMessageItem message) {
        try {
            extractionUseCase.handle(messageCodec.decode(message.getBody().toString()));
            queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
        } catch (RuntimeException exception) {
            if (message.getDequeueCount() >= positive(properties.maximumDequeueCount(), 5)) {
                moveToPoisonQueue(message, exception);
                return;
            }
            LOGGER.warn(
                    "Azure Queue segment message {} was not acknowledged: {}",
                    message.getMessageId(),
                    exception.getMessage());
        }
    }

    private void moveToPoisonQueue(QueueMessageItem message, RuntimeException processingFailure) {
        try {
            poisonQueueClient.sendMessage(message.getBody().toString());
            queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
            LOGGER.error(
                    "Azure Queue segment message {} moved to poison queue after {} deliveries: {}",
                    message.getMessageId(),
                    message.getDequeueCount(),
                    processingFailure.getMessage());
        } catch (RuntimeException poisonFailure) {
            LOGGER.warn(
                    "Could not move Azure Queue segment message {} to poison queue: {}",
                    message.getMessageId(),
                    poisonFailure.getMessage());
        }
    }

    private static int positive(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static int bounded(int value, int minimum, int maximum, int defaultValue) {
        int selected = value == 0 ? defaultValue : value;
        return Math.max(minimum, Math.min(maximum, selected));
    }
}
