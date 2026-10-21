package br.com.greenv.videoapi.domain;

import tools.jackson.databind.JsonNode;

/**
 * Where a depth job stands, in RunPod's vocabulary reduced to what the pipeline acts on.
 *
 * <p>{@code output} is the handler's manifest and is present only when {@code state} is
 * {@link State#COMPLETED}; {@code error} only when the job ended any other way.
 */
public record DepthJobStatus(String jobId, State state, JsonNode output, String error, JsonNode progress) {

    public enum State {
        /** {@code IN_QUEUE}: no worker has taken it yet, which is what a cold start looks like. */
        QUEUED,
        /** {@code IN_PROGRESS}: a GPU is computing it. */
        RUNNING,
        COMPLETED,
        /** {@code FAILED}, {@code CANCELLED} or {@code TIMED_OUT}: it will not complete. */
        FAILED
    }

    public static DepthJobStatus queued(String jobId) {
        return new DepthJobStatus(jobId, State.QUEUED, null, null, null);
    }

    public static DepthJobStatus running(String jobId) {
        return running(jobId, null);
    }

    /** {@code progress} is what the handler last reported while running, when it reported any. */
    public static DepthJobStatus running(String jobId, JsonNode progress) {
        return new DepthJobStatus(jobId, State.RUNNING, null, null, progress);
    }

    public static DepthJobStatus completed(String jobId, JsonNode output) {
        return new DepthJobStatus(jobId, State.COMPLETED, output, null, null);
    }

    public static DepthJobStatus failed(String jobId, String error) {
        return new DepthJobStatus(jobId, State.FAILED, null, error, null);
    }
}
