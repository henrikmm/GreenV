package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fills in the frame readings of segments measured before the rows existed.
 *
 * <p>Every measurement recorded from now on derives its own. This exists for the ones already
 * on record, and for any whose derivation failed because storage was briefly unreachable: the
 * query asks for measured segments with no readings at all, so a segment leaves the queue the
 * moment it has them and never comes back.
 *
 * <p>No GPU and no reprocessing. The assessments are already in object storage; this only reads
 * them. A segment whose assessment genuinely names no votes stays in the queue and is retried,
 * which costs one object read a minute and is the honest trade against writing a row that
 * claims a photograph measured nothing when the file simply was not there.
 */
@Service
public class FrameReadingsBackfillService {

    private static final Logger log = LoggerFactory.getLogger(FrameReadingsBackfillService.class);

    private final CaptureSessionStore store;
    private final CaptureSessionUseCase captureSessions;
    private final int batchSize;

    public FrameReadingsBackfillService(
            CaptureSessionStore store,
            CaptureSessionUseCase captureSessions,
            @Value("${greenv.frame-readings.batch-size:5}") int batchSize) {
        this.store = store;
        this.captureSessions = captureSessions;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelay = 40_000, fixedDelayString = "${greenv.frame-readings.interval-ms:60000}")
    public void derivePending() {
        List<CaptureSegmentDocument> pending = store.findSegmentsAwaitingFrameReadings(batchSize);
        for (CaptureSegmentDocument segment : pending) {
            try {
                captureSessions.deriveFrameReadingsFor(segment.sessionId(), segment.segmentIndex());
            } catch (RuntimeException failed) {
                log.warn(
                        "frame readings not derived for {}/{}: {}",
                        segment.sessionId(),
                        segment.segmentIndex(),
                        failed.toString());
            }
        }
    }
}
