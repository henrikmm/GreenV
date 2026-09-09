package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.MeasurementAnnouncementReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Listens for worker 2 saying it measured a segment.
 *
 * <p>Until this existed the worker published its result to {@code segment.measured.v1} and no queue
 * anywhere was bound to that key, so the broker discarded every message: the measurement reached
 * object storage and nothing in the control plane knew it had happened.
 *
 * <p>What is read out of the envelope, and why so little of it, is in
 * {@link JacksonMeasurementAnnouncementReaderAdapter}.
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
    private final MeasurementAnnouncementReader announcementReader;

    public RabbitMqMeasurementResultAdapter(
            CaptureSessionUseCase captureSessionUseCase, MeasurementAnnouncementReader announcementReader) {
        this.captureSessionUseCase = captureSessionUseCase;
        this.announcementReader = announcementReader;
    }

    @RabbitListener(queues = "${greenv.capture-queue.measured-queue:}")
    public void onMeasured(String payload) {
        SegmentMeasurementAnnouncement announcement;
        try {
            announcement = announcementReader.read(payload);
        } catch (RuntimeException exception) {
            // Nothing retried can fix a message that does not parse, and rejecting it would send it
            // round again for ever. Drop it, loudly. The Azure path has a poison queue to put one
            // in instead; this transport has nowhere.
            log.warn("discarded an unreadable measurement announcement: {}", exception.toString());
            return;
        }
        captureSessionUseCase.recordMeasurement(announcement);
    }
}
