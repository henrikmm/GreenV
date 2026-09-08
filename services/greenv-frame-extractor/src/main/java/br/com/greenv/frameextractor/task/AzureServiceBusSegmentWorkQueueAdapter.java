package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import br.com.greenv.frameextractor.service.ExtractionException;
import com.azure.core.exception.AzureException;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-service-bus")
public class AzureServiceBusSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final ServiceBusSenderClient senderClient;
    private final SegmentMessageCodec messageCodec;

    public AzureServiceBusSegmentWorkQueueAdapter(
            ServiceBusSenderClient senderClient,
            SegmentMessageCodec messageCodec) {
        this.senderClient = senderClient;
        this.messageCodec = messageCodec;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            ServiceBusMessage message = new ServiceBusMessage(messageCodec.encode(request));
            message.setContentType("application/json");
            message.setMessageId(request.idempotencyKey());
            senderClient.sendMessage(message);
        } catch (AzureException exception) {
            throw new ExtractionException(
                    "segment_retry_publish_failed",
                    "could not publish the next segment extraction attempt",
                    true,
                    exception);
        }
    }
}
