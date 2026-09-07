package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqSegmentExtractionListener {

    private final SegmentMessageCodec messageCodec;
    private final SegmentExtractionUseCase extractionUseCase;

    public RabbitMqSegmentExtractionListener(
            SegmentMessageCodec messageCodec,
            SegmentExtractionUseCase extractionUseCase) {
        this.messageCodec = messageCodec;
        this.extractionUseCase = extractionUseCase;
    }

    @RabbitListener(queues = "${greenv.capture.queue}")
    public void receive(Message message) {
        extractionUseCase.handle(messageCodec.decode(new String(message.getBody(), StandardCharsets.UTF_8)));
    }
}
