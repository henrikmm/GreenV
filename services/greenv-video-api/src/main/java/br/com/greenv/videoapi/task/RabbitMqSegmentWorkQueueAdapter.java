package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.CaptureQueueProperties;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final CaptureQueueProperties queueProperties;

    public RabbitMqSegmentWorkQueueAdapter(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CaptureQueueProperties queueProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.queueProperties = queueProperties;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            rabbitTemplate.convertAndSend(
                    queueProperties.exchange(),
                    queueProperties.routingKey(),
                    objectMapper.writeValueAsString(request),
                    message -> {
                        message.getMessageProperties().setContentType("application/json");
                        message.getMessageProperties().setMessageId(request.idempotencyKey());
                        return message;
                    });
        } catch (AmqpException | JacksonException exception) {
            throw new ApplicationException(
                    FailureKind.DEPENDENCY_UNAVAILABLE,
                    "segment_queue_unavailable",
                    "could not queue segment extraction: " + exception.getMessage());
        }
    }
}
