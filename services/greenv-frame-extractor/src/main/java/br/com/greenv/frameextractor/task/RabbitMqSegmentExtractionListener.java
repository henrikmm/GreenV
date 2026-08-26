package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.service.ExtractionException;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqSegmentExtractionListener {

    private final ObjectMapper objectMapper;
    private final SegmentExtractionUseCase extractionUseCase;

    public RabbitMqSegmentExtractionListener(
            ObjectMapper objectMapper,
            SegmentExtractionUseCase extractionUseCase) {
        this.objectMapper = objectMapper;
        this.extractionUseCase = extractionUseCase;
    }

    @RabbitListener(queues = "${greenv.capture.queue}")
    public void receive(Message message) {
        SegmentExtractionRequest request = parse(message);
        extractionUseCase.handle(request);
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
