package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.MeasurementRequest;
import br.com.greenv.frameextractor.port.MeasurementWorkQueue;
import com.azure.storage.queue.QueueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Announces a finished segment over Azure Queue Storage.
 *
 * <p>The body is the same JSON {@link RabbitMqMeasurementWorkQueueAdapter} sends, from the same
 * mapper and the same record: worker 2 reads one {@code MeasurementRequest} whichever transport
 * carried it. What Azure Queue has no room for is the envelope around it — there is no exchange, no
 * routing key, and no settable message id, so the queue is named outright and the extraction
 * idempotency key travels where it already was, inside the body as {@code idempotencyKey}.
 */
@Component
@ConditionalOnProperty(name = "greenv.measurement.enabled", havingValue = "true")
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
public class AzureQueueMeasurementWorkQueueAdapter implements MeasurementWorkQueue {

    private static final Logger LOG = LoggerFactory.getLogger(AzureQueueMeasurementWorkQueueAdapter.class);

    private final QueueClient queueClient;
    private final ObjectMapper objectMapper;

    public AzureQueueMeasurementWorkQueueAdapter(
            @Qualifier("azureMeasurementQueueClient") QueueClient queueClient, ObjectMapper objectMapper) {
        this.queueClient = queueClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(MeasurementRequest request) {
        try {
            queueClient.sendMessage(objectMapper.writeValueAsString(request));
        } catch (RuntimeException exception) {
            // Swallowed for the reason the port states: the extraction succeeded and its frames are
            // durable in object storage, so failing it here would re-run ffmpeg over a segment that
            // is already complete, and the worker can still be triggered over HTTP. RuntimeException
            // rather than AzureException alone, because a client built against an unreachable
            // account throws from the SDK's own plumbing, not only from its typed hierarchy.
            LOG.warn(
                    "could not announce segment {}/{} for measurement: {}",
                    request.sessionId(),
                    request.segmentIndex(),
                    exception.getMessage());
        }
    }
}
