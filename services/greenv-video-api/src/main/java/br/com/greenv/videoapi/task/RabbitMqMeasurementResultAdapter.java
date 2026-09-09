package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Listens for worker 2 saying it measured a segment.
 *
 * <p>Until this existed the worker published its result to {@code segment.measured.v1} and no queue
 * anywhere was bound to that key, so the broker discarded every message: the measurement reached
 * object storage and nothing in the control plane knew it had happened.
 *
 * <p>Only the identity is read off the wire. The packet itself stays in object storage under a key
 * this service derives for itself, so a malformed or hostile announcement cannot point a later read
 * somewhere else.
 */
@Component
// Both halves matter. Without the first, a deployment that publishes through Azure Queue would
// still open a Rabbit listener and retry a connection it has no reason to have; without the second,
// an unnamed queue would fail the context at startup.
@ConditionalOnExpression(
        "'${greenv.adapters.segment-queue:rabbitmq}' == 'rabbitmq'"
                + " and '${greenv.capture-queue.measured-queue:}' != ''")
public class RabbitMqMeasurementResultAdapter {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqMeasurementResultAdapter.class);

    private final CaptureSessionUseCase captureSessionUseCase;
    private final ObjectMapper objectMapper;

    public RabbitMqMeasurementResultAdapter(
            CaptureSessionUseCase captureSessionUseCase, ObjectMapper objectMapper) {
        this.captureSessionUseCase = captureSessionUseCase;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${greenv.capture-queue.measured-queue:}")
    public void onMeasured(String payload) {
        SegmentMeasurementAnnouncement announcement;
        try {
            announcement = read(payload);
        } catch (RuntimeException exception) {
            // Nothing retried can fix a message that does not parse, and rejecting it would send it
            // round again for ever. Drop it, loudly.
            log.warn("discarded an unreadable measurement announcement: {}", exception.toString());
            return;
        }
        captureSessionUseCase.recordMeasurement(announcement);
    }

    private SegmentMeasurementAnnouncement read(String payload) {
        JsonNode root = objectMapper.readTree(payload);
        return new SegmentMeasurementAnnouncement(
                UUID.fromString(root.get("sessionId").asString()),
                root.get("segmentIndex").asInt(),
                text(root, "runId"),
                root.path("mock").asBoolean(false),
                Instant.parse(root.get("measuredAt").asString()));
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }
}
