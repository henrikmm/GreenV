package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import br.com.greenv.frameextractor.service.ExtractionException;
import com.azure.core.exception.AzureException;
import com.azure.storage.queue.QueueClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
public class AzureQueueSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final QueueClient queueClient;
    private final SegmentMessageCodec messageCodec;

    public AzureQueueSegmentWorkQueueAdapter(
            @Qualifier("azureSegmentQueueClient") QueueClient queueClient,
            SegmentMessageCodec messageCodec) {
        this.queueClient = queueClient;
        this.messageCodec = messageCodec;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            queueClient.sendMessage(messageCodec.encode(request));
        } catch (AzureException exception) {
            throw new ExtractionException(
                    "segment_retry_publish_failed",
                    "could not publish the next segment extraction attempt",
                    true,
                    exception);
        }
    }
}
