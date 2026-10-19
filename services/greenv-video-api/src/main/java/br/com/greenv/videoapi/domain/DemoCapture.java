package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One capture sent by the demo phone, and everything the pipeline has done with it.
 *
 * <p>{@code state} is the last phase written. {@code meta} is the phone's
 * {@code verge-phone-capture/1} document as it arrived; unknown fields are kept, because the phone
 * is developed on its own and a field this API has not heard of yet is still the phone's record.
 *
 * <p>A demo capture is on no road. It still carries the four fields every GreenV artifact
 * carries, and says so with nulls rather than by leaving them out: {@code rodovia},
 * {@code sentido} and {@code km} are null and {@code capturadoEm} is the phone's own timestamp.
 *
 * <p>{@code frameKeys}, {@code depthJobId}, {@code manifest} and the GPU fields fill in as the
 * pipeline moves; every one is null or empty before its phase. Object keys, never URLs, are kept:
 * a URL is signed when it is read.
 */
public record DemoCapture(
        String id,
        String state,
        JsonNode meta,
        String rodovia,
        String sentido,
        Double km,
        Instant capturadoEm,
        Instant createdAt,
        Instant updatedAt,
        DemoVideo video,
        List<String> frameKeys,
        String depthJobId,
        Instant depthJobSubmittedAt,
        JsonNode manifest,
        String gpu,
        Double gpuSeconds,
        int attempts,
        List<DemoPhase> phases) {

    public DemoCapture {
        frameKeys = frameKeys == null ? List.of() : List.copyOf(frameKeys);
        phases = phases == null ? List.of() : List.copyOf(phases);
    }

    public static DemoCapture announced(String id, JsonNode meta, Instant capturadoEm, Instant now) {
        DemoPhase created = DemoPhase.of(DemoPhases.CREATED, now, now, null);
        return new DemoCapture(
                id, DemoPhases.CREATED, meta, null, null, null, capturadoEm, now, now,
                null, List.of(), null, null, null, null, null, 0, List.of(created));
    }

    /** This capture with one more line on its timeline, which becomes its state. */
    public DemoCapture append(DemoPhase phase, Instant now) {
        List<DemoPhase> next = new ArrayList<>(phases);
        next.add(phase);
        return new DemoCapture(
                id, phase.phase(), meta, rodovia, sentido, km, capturadoEm, createdAt, now,
                video, frameKeys, depthJobId, depthJobSubmittedAt, manifest, gpu, gpuSeconds,
                attempts, next);
    }

    public DemoCapture withVideo(DemoVideo value) {
        return new DemoCapture(
                id, state, meta, rodovia, sentido, km, capturadoEm, createdAt, updatedAt,
                value, frameKeys, depthJobId, depthJobSubmittedAt, manifest, gpu, gpuSeconds,
                attempts, phases);
    }

    public DemoCapture withFrameKeys(List<String> value) {
        return new DemoCapture(
                id, state, meta, rodovia, sentido, km, capturadoEm, createdAt, updatedAt,
                video, value, depthJobId, depthJobSubmittedAt, manifest, gpu, gpuSeconds,
                attempts, phases);
    }

    public DemoCapture withDepthJob(String jobId, Instant submittedAt) {
        return new DemoCapture(
                id, state, meta, rodovia, sentido, km, capturadoEm, createdAt, updatedAt,
                video, frameKeys, jobId, submittedAt, manifest, gpu, gpuSeconds,
                attempts, phases);
    }

    public DemoCapture withResult(JsonNode value, String gpuName, Double seconds) {
        return new DemoCapture(
                id, state, meta, rodovia, sentido, km, capturadoEm, createdAt, updatedAt,
                video, frameKeys, depthJobId, depthJobSubmittedAt, value, gpuName, seconds,
                attempts, phases);
    }

    public DemoCapture withAttempts(int value) {
        return new DemoCapture(
                id, state, meta, rodovia, sentido, km, capturadoEm, createdAt, updatedAt,
                video, frameKeys, depthJobId, depthJobSubmittedAt, manifest, gpu, gpuSeconds,
                value, phases);
    }

    /** Where the frames of this capture live; the handler publishes beside this directory. */
    public static String framesPrefix(String captureId) {
        return prefix(captureId) + "sampled-frames/";
    }

    /** Every object this capture owns sits under this prefix, and nothing else does. */
    public static String prefix(String captureId) {
        return "demo/" + captureId + "/";
    }
}
