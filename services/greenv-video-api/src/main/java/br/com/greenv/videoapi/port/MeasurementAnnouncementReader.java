package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;

/**
 * Reads worker 2's {@code measurement-result-v1} envelope into the identity the control plane acts
 * on.
 *
 * <p>One reader for every transport, so the queue a result arrives over can change without the
 * message changing with it. Throws when the payload cannot be read; what to do about that is the
 * transport's decision, and RabbitMQ and Azure Queue answer it differently.
 */
public interface MeasurementAnnouncementReader {

    SegmentMeasurementAnnouncement read(String payload);
}
