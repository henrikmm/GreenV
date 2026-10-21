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
        Double sourceDurationSeconds,
        Video video) {

    public DepthJobRequest {
        frames = List.copyOf(frames);
    }

    /** A job by frames, the dialect the field pipeline and the API's own sampling use. */
    public DepthJobRequest(
            String captureId,
            List<Frame> frames,
            double fps,
            int processRes,
            int maxFrames,
            Double sourceDurationSeconds) {
        this(captureId, frames, fps, processRes, maxFrames, sourceDurationSeconds, null);
    }

    /**
     * A job by video: the GPU machine samples it and publishes the frames under
     * {@link Video#framesPrefix()}, so they cross from Brazil to the GPU once, as one file.
     */
    public static DepthJobRequest ofVideo(String captureId, Video video, int processRes, int maxFrames) {
        return new DepthJobRequest(captureId, List.of(), video.fps(), processRes, maxFrames, null, video);
    }

    /** The video's key and the sampling rule, the same one the API applies when it samples. */
    public record Video(String key, double fps, int maxFrames, int longEdge, String framesPrefix) {
    }

    public record Frame(String name, String key) {
    }
}
