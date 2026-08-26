package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.CaptureQueueProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import br.com.greenv.frameextractor.service.ExtractionException;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class SegmentTaskPublisher implements SegmentWorkQueue {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final CaptureQueueProperties properties;

    public SegmentTaskPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CaptureQueueProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            rabbitTemplate.convertAndSend(
                    properties.exchange(),
                    properties.routingKey(),
                    objectMapper.writeValueAsString(request),
                    message -> {
                        message.getMessageProperties().setContentType("application/json");
                        message.getMessageProperties().setMessageId(request.idempotencyKey());
                        return message;
                    });
        } catch (AmqpException | JacksonException exception) {
            throw new ExtractionException(
                    "segment_retry_publish_failed",
                    "could not publish the next segment extraction attempt",
                    true,
                    exception);
        }
    }
}
