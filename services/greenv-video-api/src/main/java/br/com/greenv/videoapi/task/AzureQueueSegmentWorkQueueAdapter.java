package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.SegmentMessageSerializer;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import com.azure.core.exception.AzureException;
import com.azure.storage.queue.QueueClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
public class AzureQueueSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final QueueClient queueClient;
    private final SegmentMessageSerializer messageSerializer;

    public AzureQueueSegmentWorkQueueAdapter(
            @Qualifier("azureSegmentQueueClient") QueueClient queueClient,
            SegmentMessageSerializer messageSerializer) {
        this.queueClient = queueClient;
        this.messageSerializer = messageSerializer;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            queueClient.sendMessage(messageSerializer.serialize(request));
        } catch (AzureException exception) {
            throw unavailable(exception);
        }
    }

    private static ApplicationException unavailable(Exception exception) {
        return new ApplicationException(
                FailureKind.DEPENDENCY_UNAVAILABLE,
                "segment_queue_unavailable",
                "could not queue segment extraction: " + exception.getMessage());
    }
}
