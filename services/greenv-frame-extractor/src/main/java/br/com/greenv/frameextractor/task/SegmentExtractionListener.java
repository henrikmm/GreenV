package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.CaptureQueueProperties;
import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.service.ExtractionException;
import br.com.greenv.frameextractor.service.SegmentExtractionService;
import br.com.greenv.frameextractor.storage.CaptureSegmentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class SegmentExtractionListener {

    private final ObjectMapper objectMapper;
    private final SegmentExtractionService service;
    private final CaptureSegmentRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final CaptureQueueProperties queueProperties;
    private final ExtractorProperties extractorProperties;
    private final Clock clock;

    public SegmentExtractionListener(
            ObjectMapper objectMapper,
            SegmentExtractionService service,
            CaptureSegmentRepository repository,
            RabbitTemplate rabbitTemplate,
            CaptureQueueProperties queueProperties,
            ExtractorProperties extractorProperties,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.service = service;
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.queueProperties = queueProperties;
        this.extractorProperties = extractorProperties;
        this.clock = clock;
    }

    @RabbitListener(queues = "${greenv.capture.queue}")
    public void receive(Message message) {
        SegmentExtractionRequest request = parse(message);
        try {
            if (!"ready".equals(repository.state(request))) {
                service.extract(request);
            }
        } catch (ExtractionException exception) {
            boolean retry = exception.retryable()
                    && request.attempt() + 1 < extractorProperties.maxAttempts();
            if (retry) {
                republish(request.nextAttempt());
            } else {
                repository.markError(request, exception, clock.instant());
            }
        }
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

    private void republish(SegmentExtractionRequest request) {
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
            throw new ExtractionException(
                    "segment_retry_publish_failed",
                    "could not publish the next segment extraction attempt",
                    true,
                    exception);
        }
    }
}
