package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.api.ApiException;
import br.com.greenv.videoapi.config.CaptureQueueProperties;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
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
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "segment_queue_unavailable",
                    "could not queue segment extraction: " + exception.getMessage());
        }
    }
}
