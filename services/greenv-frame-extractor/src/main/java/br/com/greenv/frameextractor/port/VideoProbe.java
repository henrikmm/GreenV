package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.MediaProbeResult;
import java.nio.file.Path;

/** Reads technical metadata from a video without prescribing a probe provider. */
public interface VideoProbe {

    MediaProbeResult probe(Path source);
}
