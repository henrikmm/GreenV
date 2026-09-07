package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.AzureServiceBusProperties;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import com.azure.core.exception.AzureException;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-service-bus")
public class AzureServiceBusSegmentExtractionPollerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureServiceBusSegmentExtractionPollerAdapter.class);

    private final ServiceBusReceiverClient receiverClient;
    private final SegmentMessageCodec messageCodec;
    private final SegmentExtractionUseCase extractionUseCase;
    private final AzureServiceBusProperties properties;

    public AzureServiceBusSegmentExtractionPollerAdapter(
            ServiceBusReceiverClient receiverClient,
            SegmentMessageCodec messageCodec,
            SegmentExtractionUseCase extractionUseCase,
            AzureServiceBusProperties properties) {
        this.receiverClient = receiverClient;
        this.messageCodec = messageCodec;
        this.extractionUseCase = extractionUseCase;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${greenv.queue.poll-delay-ms:1000}")
    public void poll() {
        try {
            receiverClient.receiveMessages(
                            bounded(properties.maximumMessages(), 1, 100, 1),
                            Duration.ofSeconds(positive(properties.receiveTimeoutSeconds(), 10)))
                    .forEach(this::process);
        } catch (AzureException exception) {
            LOGGER.warn("Could not poll Azure Service Bus segment queue: {}", exception.getMessage());
        }
    }

    void process(ServiceBusReceivedMessage message) {
        try {
            extractionUseCase.handle(messageCodec.decode(message.getBody().toString()));
            receiverClient.complete(message);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Azure Service Bus segment message {} was not completed: {}",
                    message.getMessageId(),
                    exception.getMessage());
            abandon(message);
        }
    }

    private void abandon(ServiceBusReceivedMessage message) {
        try {
            receiverClient.abandon(message);
        } catch (AzureException exception) {
            LOGGER.warn("Could not abandon Azure Service Bus message {}: {}", message.getMessageId(), exception.getMessage());
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
