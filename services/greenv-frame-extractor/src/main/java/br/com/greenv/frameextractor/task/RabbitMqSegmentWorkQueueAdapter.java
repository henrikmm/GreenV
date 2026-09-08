package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.CaptureQueueProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import br.com.greenv.frameextractor.service.ExtractionException;
import org.springframework.amqp.AmqpException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqSegmentWorkQueueAdapter implements SegmentWorkQueue {

    private final RabbitTemplate rabbitTemplate;
    private final SegmentMessageCodec messageCodec;
    private final CaptureQueueProperties queueProperties;

    public RabbitMqSegmentWorkQueueAdapter(
            RabbitTemplate rabbitTemplate,
            SegmentMessageCodec messageCodec,
            CaptureQueueProperties queueProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageCodec = messageCodec;
        this.queueProperties = queueProperties;
    }

    @Override
    public void publish(SegmentExtractionRequest request) {
        try {
            rabbitTemplate.convertAndSend(
                    queueProperties.exchange(),
                    queueProperties.routingKey(),
                    messageCodec.encode(request),
                    message -> {
                        message.getMessageProperties().setContentType("application/json");
                        message.getMessageProperties().setMessageId(request.idempotencyKey());
                        return message;
                    });
        } catch (AmqpException exception) {
            throw new ExtractionException(
                    "segment_retry_publish_failed",
                    "could not publish the next segment extraction attempt",
                    true,
                    exception);
        }
    }
}
