package br.com.greenv.videoapi.domain;

/**
 * The demo's GPU endpoint as its settings and its workers stand right now.
 *
 * <p>{@code workersMin} is the only setting the demo changes: at 1 RunPod keeps one worker alive
 * between jobs, so a capture skips the cold start, and bills for that worker all the time it is
 * kept. {@code workersMax} and {@code idleTimeoutSeconds} are shown, never written.
 */
public record DemoGpuState(int workersMin, int workersMax, int idleTimeoutSeconds, Workers workers) {

    /** Whether a worker is kept alive between jobs. */
    public boolean keepWarm() {
        return workersMin >= 1;
    }

    /** RunPod's own count of the endpoint's workers, by state. */
    public record Workers(int idle, int initializing, int ready, int running, int throttled, int unhealthy) {
    }
}
