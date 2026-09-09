package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.AzureQueueProperties;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.MeasurementAnnouncementReader;
import com.azure.core.exception.AzureException;
import com.azure.core.util.Context;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collects worker 2's measurements over Azure Queue Storage.
 *
 * <p>The same hop {@link RabbitMqMeasurementResultAdapter} makes, through a transport that pushes
 * nothing: this polls, and a message it takes is invisible to other readers for the visibility
 * timeout rather than held on a channel. The body is unchanged — one
 * {@code measurement-result-v1} envelope, read by the same {@link MeasurementAnnouncementReader}
 * — so which queue carried it is not something the control plane can tell.
 *
 * <p>An announcement that cannot be read is kept rather than dropped. The Rabbit listener has
 * nowhere to put one, so it logs and discards; here the poison queue worker 1 already established
 * for this transport leaves it where a human can go and look at it.
 */
@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
// A deployment without worker 2 names no queue, and must not poll one.
@ConditionalOnExpression("'${greenv.queue.azure-queue.measured-queue:}' != ''")
public class AzureQueueMeasurementResultAdapter {

    private static final Logger log = LoggerFactory.getLogger(AzureQueueMeasurementResultAdapter.class);

    private final QueueClient queueClient;
    private final QueueClient poisonQueueClient;
    private final MeasurementAnnouncementReader announcementReader;
    private final CaptureSessionUseCase captureSessionUseCase;
    private final AzureQueueProperties properties;

    public AzureQueueMeasurementResultAdapter(
            @Qualifier("azureMeasuredQueueClient") QueueClient queueClient,
            @Qualifier("azureMeasuredPoisonQueueClient") QueueClient poisonQueueClient,
            MeasurementAnnouncementReader announcementReader,
            CaptureSessionUseCase captureSessionUseCase,
            AzureQueueProperties properties) {
        this.queueClient = queueClient;
        this.poisonQueueClient = poisonQueueClient;
        this.announcementReader = announcementReader;
        this.captureSessionUseCase = captureSessionUseCase;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${greenv.queue.poll-delay-ms:1000}")
    public void poll() {
        try {
            queueClient
                    .receiveMessages(
                            bounded(properties.maximumMessages(), 1, 32, 1),
                            Duration.ofSeconds(positive(properties.visibilityTimeoutSeconds(), 300)),
                            Duration.ofSeconds(10),
                            Context.NONE)
                    .forEach(this::process);
        } catch (AzureException exception) {
            log.warn("Could not poll the Azure Queue measurement results: {}", exception.getMessage());
        }
    }

    void process(QueueMessageItem message) {
        try {
            captureSessionUseCase.recordMeasurement(announcementReader.read(message.getBody().toString()));
            queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
        } catch (RuntimeException exception) {
            // Not deleted, so it reappears once the visibility timeout expires: that is the whole of
            // the retry, and dequeueCount is the attempt counter Azure keeps on our behalf.
            if (message.getDequeueCount() >= positive(properties.maximumDequeueCount(), 5)) {
                moveToPoisonQueue(message, exception);
                return;
            }
            log.warn(
                    "Azure Queue measurement announcement {} was not acknowledged: {}",
                    message.getMessageId(),
                    exception.getMessage());
        }
    }

    private void moveToPoisonQueue(QueueMessageItem message, RuntimeException processingFailure) {
        try {
            poisonQueueClient.sendMessage(message.getBody().toString());
            queueClient.deleteMessage(message.getMessageId(), message.getPopReceipt());
            log.error(
                    "Azure Queue measurement announcement {} moved to poison queue after {} deliveries: {}",
                    message.getMessageId(),
                    message.getDequeueCount(),
                    processingFailure.getMessage());
        } catch (RuntimeException poisonFailure) {
            log.warn(
                    "Could not move Azure Queue measurement announcement {} to poison queue: {}",
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
