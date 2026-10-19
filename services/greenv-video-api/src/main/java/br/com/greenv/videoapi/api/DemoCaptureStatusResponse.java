package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoPhases;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * A capture as the phone sees it — contract v1's {@code GET /v1/captures/{id}}, field for field:
 * no paths, no hashes it did not ask for.
 *
 * <p>{@code result} is null until the capture is {@code fetched}, and then names the run, so the
 * phone can show which reconstruction its video became.
 */
public record DemoCaptureStatusResponse(
        String id,
        String state,
        List<DemoPhaseResponse> phases,
        Video video,
        Map<String, Object> result) {

    public static DemoCaptureStatusResponse from(DemoCapture capture) {
        return new DemoCaptureStatusResponse(
                capture.id(),
                capture.state(),
                capture.phases().stream().map(DemoPhaseResponse::from).toList(),
                capture.video() == null
                        ? null
                        : new Video(capture.video().bytes(), capture.video().transferMs()),
                result(capture));
    }

    private static Map<String, Object> result(DemoCapture capture) {
        if (!DemoPhases.FETCHED.equals(capture.state()) || capture.manifest() == null) {
            return null;
        }
        JsonNode manifest = capture.manifest();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", manifest.path("run_id").isString() ? manifest.path("run_id").asString() : null);
        result.put("frames", manifest.path("frames").path("count").isNumber()
                ? manifest.path("frames").path("count").asInt()
                : null);
        result.put("gpu", capture.gpu());
        result.put("gpuSeconds", capture.gpuSeconds());
        result.put("mock", manifest.path("mock").asBoolean(false));
        return result;
    }

    public record Video(long bytes, long transferMs) {
    }
}
