package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.ScalePlan;
import java.nio.file.Path;
import java.util.List;

/** Extracts sampled frames through a replaceable media-processing provider. */
public interface FrameSampler {

    List<Path> extract(Path source, Path outputDirectory, SamplingPlan sampling, ScalePlan scale);
}
