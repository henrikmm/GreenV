package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.SegmentMessageSerializer;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import com.azure.core.exception.AzureException;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-service-bus")
public class AzureServiceBusSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final ServiceBusSenderClient senderClient;
    private final SegmentMessageSerializer messageSerializer;

    public AzureServiceBusSegmentWorkQueueAdapter(
            ServiceBusSenderClient senderClient,
            SegmentMessageSerializer messageSerializer) {
        this.senderClient = senderClient;
        this.messageSerializer = messageSerializer;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            ServiceBusMessage message = new ServiceBusMessage(messageSerializer.serialize(request));
            message.setContentType("application/json");
            message.setMessageId(request.idempotencyKey());
            senderClient.sendMessage(message);
        } catch (AzureException exception) {
            throw new ApplicationException(
                    FailureKind.DEPENDENCY_UNAVAILABLE,
                    "segment_queue_unavailable",
                    "could not queue segment extraction: " + exception.getMessage());
        }
    }
}
