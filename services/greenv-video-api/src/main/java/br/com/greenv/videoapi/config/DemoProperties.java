package br.com.greenv.videoapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The FIAP Next demo under {@code /v1/demo/}. Off unless {@code GREENV_DEMO_ENABLED} is true, and
 * while it is off none of the demo's beans exist and none of these values are checked, so a
 * deployment that never heard of the demo behaves exactly as before.
 *
 * <p>{@code maxVideoBytes} is 95 MiB because the API sits behind Cloudflare, which cuts a request
 * body at 100 MB; refusing at 95 MiB answers with a 413 the phone can show instead of a connection
 * reset it cannot explain. {@code maxFrames} is what the demo endpoint's GPU is configured to hold
 * ({@code GREENV_DEPTH_MAX_FRAMES} on the handler); asking for more is refused there.
 *
 * <p>{@code jobDeadline} runs from submission and covers the cold start, the model load and the
 * inference together. When it passes the job is cancelled, because a serverless worker that is
 * still running bills whether or not anyone is waiting for it.
 */
@ConfigurationProperties("greenv.demo")
public record DemoProperties(
        boolean enabled,
        String pairingCode,
        long maxVideoBytes,
        int maxFrames,
        double defaultFps,
        int longEdge,
        int processRes,
        String depthAdapter,
        Duration jobDeadline,
        Duration extractionLease,
        Duration pollLease,
        Duration signedUrlTtl,
        int retentionDays,
        String publicBaseUrl,
        String ffmpeg,
        String ffprobe,
        Runpod runpod,
        String sampleOn) {

    /** Below this a pairing code is guessable by anyone willing to send a few thousand requests. */
    public static final int MIN_PAIRING_CODE_LENGTH = 6;

    // Two constructors, so the binder is told which one is the properties' own.
    @ConstructorBinding
    public DemoProperties {
        if (depthAdapter == null || depthAdapter.isBlank()) {
            depthAdapter = "runpod";
        }
        if (runpod == null) {
            runpod = new Runpod(null, null, null);
        }
        if (sampleOn == null || sampleOn.isBlank()) {
            sampleOn = "api";
        }
        if (!sampleOn.equals("api") && !sampleOn.equals("gpu")) {
            throw new IllegalArgumentException("greenv.demo.sample-on must be api or gpu");
        }
        if (enabled) {
            if (pairingCode == null || pairingCode.strip().length() < MIN_PAIRING_CODE_LENGTH) {
                throw new IllegalArgumentException(
                        "greenv.demo.pairing-code must be at least " + MIN_PAIRING_CODE_LENGTH
                                + " characters when the demo is enabled");
            }
            if (maxVideoBytes <= 0 || maxFrames < 2 || !(defaultFps > 0) || longEdge <= 0 || processRes <= 0) {
                throw new IllegalArgumentException("demo limits must be positive, and max-frames at least 2");
            }
            if (!depthAdapter.equals("runpod") && !depthAdapter.equals("mock")) {
                throw new IllegalArgumentException("greenv.demo.depth-adapter must be runpod or mock");
            }
            if (notPositive(jobDeadline) || notPositive(extractionLease) || notPositive(pollLease)
                    || notPositive(signedUrlTtl)) {
                throw new IllegalArgumentException("demo durations must be positive");
            }
            if (retentionDays <= 0) {
                throw new IllegalArgumentException("greenv.demo.retention-days must be positive");
            }
        }
    }

    /** Every setting but where the video is sampled, which then stays on the API. */
    public DemoProperties(
            boolean enabled,
            String pairingCode,
            long maxVideoBytes,
            int maxFrames,
            double defaultFps,
            int longEdge,
            int processRes,
            String depthAdapter,
            Duration jobDeadline,
            Duration extractionLease,
            Duration pollLease,
            Duration signedUrlTtl,
            int retentionDays,
            String publicBaseUrl,
            String ffmpeg,
            String ffprobe,
            Runpod runpod) {
        this(enabled, pairingCode, maxVideoBytes, maxFrames, defaultFps, longEdge, processRes, depthAdapter,
                jobDeadline, extractionLease, pollLease, signedUrlTtl, retentionDays, publicBaseUrl, ffmpeg,
                ffprobe, runpod, "api");
    }

    /** Whether the GPU machine samples the video, instead of this API. */
    public boolean samplesOnGpu() {
        return "gpu".equals(sampleOn);
    }

    /** Never prints the pairing code: a record's own toString would, into any log that shows it. */
    @Override
    public String toString() {
        return "DemoProperties[enabled=" + enabled + ", depthAdapter=" + depthAdapter + ", sampleOn=" + sampleOn
                + ", maxVideoBytes=" + maxVideoBytes + ", maxFrames=" + maxFrames + ", runpod=" + runpod
                + ", pairingCode=" + (pairingCode == null || pairingCode.isBlank() ? "<unset>" : "<set>") + "]";
    }

    private static boolean notPositive(Duration duration) {
        return duration == null || duration.isNegative() || duration.isZero();
    }

    /**
     * The demo's own RunPod endpoint ({@code greenv-demo-depth}), never the field pipeline's: the
     * two are provisioned, sized and billed separately.
     *
     * <p>{@code baseUrl} is the serverless job API ({@code /run}, {@code /status}, {@code /health});
     * {@code restBaseUrl} is RunPod's management API, where the endpoint's own settings live —
     * the demo reads and changes {@code workersMin} there and nothing else.
     */
    public record Runpod(String baseUrl, String endpointId, String apiKey, String restBaseUrl) {

        // Two constructors, so the binder is told which one is the properties' own.
        @ConstructorBinding
        public Runpod {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.runpod.ai/v2";
            }
            if (restBaseUrl == null || restBaseUrl.isBlank()) {
                restBaseUrl = "https://rest.runpod.io/v1";
            }
        }

        /** The job API only; the management API keeps its default. */
        public Runpod(String baseUrl, String endpointId, String apiKey) {
            this(baseUrl, endpointId, apiKey, null);
        }

        /** Never prints the API key. */
        @Override
        public String toString() {
            return "Runpod[baseUrl=" + baseUrl + ", restBaseUrl=" + restBaseUrl + ", endpointId=" + endpointId
                    + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<set>") + "]";
        }
    }
}
