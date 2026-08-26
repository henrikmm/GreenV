package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.service.ExtractionException;
import br.com.greenv.frameextractor.service.SegmentExtractionHandler;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class SegmentExtractionListener {

    private final ObjectMapper objectMapper;
    private final SegmentExtractionHandler handler;

    public SegmentExtractionListener(
            ObjectMapper objectMapper,
            SegmentExtractionHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @RabbitListener(queues = "${greenv.capture.queue}")
    public void receive(Message message) {
        SegmentExtractionRequest request = parse(message);
        handler.handle(request);
    }

    private SegmentExtractionRequest parse(Message message) {
        try {
            return objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    SegmentExtractionRequest.class);
        } catch (JacksonException exception) {
            throw new ExtractionException(
                    "invalid_segment_message",
                    "RabbitMQ message is not a segment extraction request",
                    false,
                    exception);
        }
    }
}
