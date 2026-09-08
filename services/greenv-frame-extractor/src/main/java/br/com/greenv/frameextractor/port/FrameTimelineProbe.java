package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import java.nio.file.Path;
import java.util.List;

/** Reads presentation timestamps for every encoded video frame. */
public interface FrameTimelineProbe {

    List<EncodedFrameTimestamp> probe(Path source);
}
