package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.MeasurementQuery;
import br.com.greenv.videoapi.domain.MeasurementSort;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Readings across every session, ordered by whatever the caller is deciding with.
 *
 * <p>Separate from the capture routes because it answers a different question. Those are about one
 * capture and are keyed by its id; this one is the feed a dashboard opens on, and it exists so a
 * client does not have to walk every session to find the handful that were measured.
 *
 * <p>Ordering and filtering are the route's job, not the client's. A browser that fetches two
 * hundred rows and sorts them is right only until there are more than two hundred, at which point
 * its first page is the tallest of an arbitrary slice and looks exactly like the tallest there is.
 */
@RestController
@RequestMapping("/v2/measurements")
public class MeasurementController {

    private final CaptureSessionUseCase captureSessionUseCase;

    public MeasurementController(CaptureSessionUseCase captureSessionUseCase) {
        this.captureSessionUseCase = captureSessionUseCase;
    }

    @GetMapping
    PageResponse<CaptureSegmentResponse> list(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant capturedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant capturedTo,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return PageResponse.from(
                captureSessionUseCase.listMeasurements(queryOf(sort, level, capturedFrom, capturedTo, q, limit, offset)),
                segment -> CaptureSegmentResponse.from(segment, baseUrl));
    }

    /**
     * The counters for the same filter, over every page of it.
     *
     * <p>Its own route because it does not change as a reader scrolls, and because a count that
     * described only the page would repeat the defect this controller just stopped making.
     */
    @GetMapping("/summary")
    MeasurementSummaryResponse summary(
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant capturedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant capturedTo,
            @RequestParam(required = false) String q) {
        return MeasurementSummaryResponse.from(captureSessionUseCase.summariseMeasurements(
                queryOf(null, level, capturedFrom, capturedTo, q, 0, 0)));
    }

    private static MeasurementQuery queryOf(
            String sort, Integer level, Instant capturedFrom, Instant capturedTo, String q, int limit, int offset) {
        return new MeasurementQuery(
                MeasurementSort.of(sort), level, capturedFrom, capturedTo, q, limit, offset);
    }
}
