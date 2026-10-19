package br.com.greenv.videoapi.domain;

import java.util.List;

/**
 * One depth job: frames by object key, never by bytes.
 *
 * <p>The shape of {@code greenv.depth-job/1}
 * ({@code services/greenv-depth-runpod/contract/depth-job-v1.example.json}). The handler derives
 * where it publishes from the keys' common directory, so frames under
 * {@code demo/<id>/sampled-frames/} yield artifacts under {@code demo/<id>/depth/<run_id>/}.
 */
public record DepthJobRequest(
        String captureId,
        List<Frame> frames,
        double fps,
        int processRes,
        int maxFrames,
        Double sourceDurationSeconds) {

    public DepthJobRequest {
        frames = List.copyOf(frames);
    }

    public record Frame(String name, String key) {
    }
}
