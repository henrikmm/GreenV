package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.MeasurementProjection;
import br.com.greenv.videoapi.domain.SegmentPlace;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.PlaceNameResolver;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Gives every measured stretch a street name, a few at a time.
 *
 * <p>Deliberately not part of recording a measurement. That path runs on a queue message the
 * worker will redeliver if it fails, and hanging it on a third-party HTTP call would let a
 * geocoder outage turn into a measurement that never gets recorded. Here the worst case is a
 * stretch that shows its coordinate for another minute.
 *
 * <p>One stretch per second, which is what Nominatim's usage policy asks of anyone. The row is
 * the cache: a coordinate is asked about once, ever, and a lookup that found nothing still writes
 * a timestamp so it is not asked again every minute. Re-running a reading is then a matter of
 * clearing {@code place_resolved_at}, which is a normal UPDATE and costs nothing.
 */
@Service
public class PlaceResolutionService {

    private static final Logger log = LoggerFactory.getLogger(PlaceResolutionService.class);

    /** What the policy asks: no more than one request a second, and we leave headroom. */
    private static final long SPACING_MILLIS = 1_100;

    private final CaptureSessionStore store;
    private final ObjectProvider<PlaceNameResolver> resolver;
    private final Clock clock;
    private final int batchSize;

    public PlaceResolutionService(
            CaptureSessionStore store,
            ObjectProvider<PlaceNameResolver> resolver,
            Clock clock,
            @Value("${greenv.places.batch-size:10}") int batchSize) {
        this.store = store;
        this.resolver = resolver;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelay = 20_000, fixedDelayString = "${greenv.places.interval-ms:60000}")
    public void resolvePending() {
        PlaceNameResolver available = resolver.getIfAvailable();
        if (available == null) {
            return;
        }
        List<CaptureSegmentDocument> pending = store.findSegmentsAwaitingPlace(batchSize);
        for (CaptureSegmentDocument segment : pending) {
            MeasurementProjection projection = segment.measurement();
            if (projection == null || projection.trackCenterLat() == null) {
                continue;
            }
            SegmentPlace place = available.resolve(
                    projection.trackCenterLat(), projection.trackCenterLon());
            if (place == null) {
                // The lookup itself failed. Leaving the row untouched is what makes the next run
                // try again, and writing "nothing here" would make the failure permanent.
                log.debug("place unresolved for {}/{}, will retry",
                        segment.sessionId(), segment.segmentIndex());
                continue;
            }
            store.recordPlace(segment.sessionId(), segment.segmentIndex(), place);
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(SPACING_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
