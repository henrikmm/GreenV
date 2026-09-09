package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.CaptureQueueProperties;
import br.com.greenv.frameextractor.config.MeasurementQueueProperties;
import br.com.greenv.frameextractor.domain.MeasurementRequest;
import br.com.greenv.frameextractor.port.MeasurementWorkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
// Written as an expression rather than a second @ConditionalOnProperty so that this stays the
// publisher for every transport that is not azure-queue. The port must always have exactly one
// implementation: naming rabbitmq here instead would leave a deployment with sqs or
// azure-service-bus and measurement enabled with no MeasurementWorkQueue bean at all, and a worker
// that fails to start is how every uploaded segment once ended up sitting in `queued`.
@ConditionalOnExpression(
        "'${greenv.measurement.enabled:false}' == 'true'"
                + " and '${greenv.adapters.segment-queue:rabbitmq}' != 'azure-queue'")
public class RabbitMqMeasurementWorkQueueAdapter implements MeasurementWorkQueue {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitMqMeasurementWorkQueueAdapter.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final CaptureQueueProperties captureProperties;
    private final MeasurementQueueProperties measurementProperties;

    public RabbitMqMeasurementWorkQueueAdapter(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            CaptureQueueProperties captureProperties,
            MeasurementQueueProperties measurementProperties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.captureProperties = captureProperties;
        this.measurementProperties = measurementProperties;
    }

    @Override
    public void publish(MeasurementRequest request) {
        try {
            // Serialised here rather than handed to the template as an object. No Jackson message
            // converter is configured on this RabbitTemplate, so the default SimpleMessageConverter
            // would reject a record outright — the same reason the segment adapter encodes first.
            // The consumer is a Node worker, so JSON is the contract rather than a convenience.
            String payload = objectMapper.writeValueAsString(request);
            rabbitTemplate.convertAndSend(
                    captureProperties.exchange(),
                    measurementProperties.routingKey(),
                    payload,
                    message -> {
                        message.getMessageProperties().setContentType("application/json");
                        // The extraction idempotency key, so a redelivered segment announces
                        // itself under the same identity it was extracted under.
                        message.getMessageProperties().setMessageId(request.idempotencyKey());
                        return message;
                    });
        } catch (RuntimeException exception) {
            // RuntimeException, not just AmqpException and JacksonException. The invariant below
            // is that a completed extraction is never failed by its announcement, and the two
            // named types do not cover everything convertAndSend can throw — a closed connection
            // factory raises IllegalStateException, which would otherwise propagate past
            // markReady and mark a fully published segment as errored.
            // Deliberately swallowed. The extraction succeeded and its frames are durable in
            // object storage; failing it here would re-run ffmpeg over a segment that is already
            // complete. The measurement worker can also be triggered over HTTP, so an unannounced
            // segment is recoverable, and a re-extracted one is wasted work.
            LOG.warn(
                    "could not announce segment {}/{} for measurement: {}",
                    request.sessionId(),
                    request.segmentIndex(),
                    exception.getMessage());
        }
    }
}
