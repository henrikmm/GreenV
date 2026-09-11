package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Measured segments across every session, newest first.
 *
 * <p>Separate from the capture routes because it answers a different question. Those are about
 * one capture and are keyed by its id; this one is the feed a map opens on, and it exists so a
 * dashboard does not have to walk every session to find the handful that were measured.
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
            @RequestParam(defaultValue = "0") int limit, @RequestParam(defaultValue = "0") int offset) {
        var query = new CaptureSessionQuery(null, null, null, true, limit, offset);
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return PageResponse.from(
                captureSessionUseCase.listMeasurements(query),
                segment -> CaptureSegmentResponse.from(segment, baseUrl));
    }
}
