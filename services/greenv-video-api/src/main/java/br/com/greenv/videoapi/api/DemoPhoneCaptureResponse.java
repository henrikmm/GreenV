package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoVideoProbe;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A capture as the site lists it: {@code PhoneCapture} in
 * {@code measurement/app/src/lib/phone-link.ts}, field for field, so the studio's own list reads
 * the API without a translation layer. {@code events} is the timeline the phone calls
 * {@code phases}.
 *
 * <p>The phone's fields — settings, what the camera applied, the orbit, the tape truth, the
 * sampling it asked for — are passed through as it sent them, or null when it sent none.
 */
public record DemoPhoneCaptureResponse(
        String id,
        String state,
        String capturedAt,
        Long durationMs,
        JsonNode settings,
        JsonNode applied,
        JsonNode orbit,
        JsonNode truth,
        JsonNode sampling,
        Video video,
        List<DemoPhaseResponse> events) {

    public static DemoPhoneCaptureResponse from(DemoCapture capture) {
        JsonNode meta = capture.meta();
        JsonNode durationMs = meta.path("durationMs");
        return new DemoPhoneCaptureResponse(
                capture.id(),
                capture.state(),
                meta.path("capturedAt").isString() ? meta.path("capturedAt").asString() : null,
                durationMs.isNumber() ? durationMs.asLong() : null,
                field(meta, "settings"),
                field(meta, "applied"),
                field(meta, "orbit"),
                field(meta, "truth"),
                field(meta, "sampling"),
                capture.video() == null
                        ? null
                        : new Video(
                                capture.video().bytes(),
                                capture.video().transferMs(),
                                capture.video().megabytesPerSecond(),
                                capture.video().probe()),
                capture.phases().stream().map(DemoPhaseResponse::from).toList());
    }

    private static JsonNode field(JsonNode meta, String name) {
        JsonNode value = meta.get(name);
        return value == null || value.isNull() || value.isMissingNode() ? null : value;
    }

    public record Video(long bytes, long transferMs, Double megabytesPerSecond, DemoVideoProbe probe) {
    }
}
