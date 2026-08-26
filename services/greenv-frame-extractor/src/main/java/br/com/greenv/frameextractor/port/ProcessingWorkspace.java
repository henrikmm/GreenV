package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import java.nio.file.Path;

/** Ephemeral disk used by ffmpeg. Nothing placed here is a durable artifact. */
public interface ProcessingWorkspace {

    Path create(SegmentExtractionRequest request);

    void clean(Path workspace);
}
