package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.api.ApiException;
import br.com.greenv.videoapi.config.CaptureProperties;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class SegmentTaskPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final CaptureProperties properties;

    public SegmentTaskPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CaptureProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

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
