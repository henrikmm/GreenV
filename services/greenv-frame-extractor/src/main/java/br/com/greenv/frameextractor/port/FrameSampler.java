package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.ScalePlan;
import java.nio.file.Path;
import java.util.List;

/** Extracts sampled frames through a replaceable media-processing provider. */
public interface FrameSampler {

    /** Uniform sampling by rate. Used by the legacy whole-video path, which has no telemetry. */
    List<Path> extract(Path source, Path outputDirectory, SamplingPlan sampling, ScalePlan scale);

    /**
     * Extracts exactly the frames named by {@code presentationTimeNanos}, in order.
     *
     * <p>Needed because frames spaced by distance are not spaced by time: a rate filter cannot
     * express "these frames and no others".
     *
     * @param frameIndices encoded-frame indices in presentation order, ascending
     */
    List<Path> extractExact(
            Path source, Path outputDirectory, List<Integer> frameIndices, ScalePlan scale);
}
