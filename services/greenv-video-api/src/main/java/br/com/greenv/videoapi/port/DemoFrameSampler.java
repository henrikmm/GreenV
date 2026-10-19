package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.DemoSampling;
import java.nio.file.Path;

/** Turns a demo video into the JPEG frames a depth job is computed from. */
public interface DemoFrameSampler {

    /**
     * Writes {@code frame-0001.jpg}, {@code frame-0002.jpg}… into {@code outputDirectory}.
     *
     * @param fps the rate asked for; lowered when the clip is longer than {@code maxFrames} allows
     * @param longEdge the longest side of a frame, in pixels; smaller videos are not enlarged
     */
    DemoSampling sample(Path video, Path outputDirectory, double fps, int maxFrames, int longEdge);
}
