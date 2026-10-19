package br.com.greenv.videoapi.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * The frames sampled from one demo video, in order, and the plan that produced them.
 *
 * <p>{@code capped} says the clip was longer than the frame budget at the asked rate, so the rate
 * was lowered to spread the budget over the whole clip rather than spend it on its first seconds.
 */
public record DemoSampling(
        List<Path> frames,
        double effectiveFps,
        boolean capped,
        int requestedCount,
        int width,
        int height,
        DemoVideoProbe probe) {

    public DemoSampling {
        frames = List.copyOf(frames);
    }
}
