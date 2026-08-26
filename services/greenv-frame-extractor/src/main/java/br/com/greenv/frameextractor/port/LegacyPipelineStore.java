package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.FrameManifest;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;
import tools.jackson.databind.node.ObjectNode;

/** Artifact boundary for the deprecated whole-video workflow. */
public interface LegacyPipelineStore {

    Path resolve(String reference);

    ObjectNode readStatus(String statusReference);

    void updateStatus(String statusReference, Consumer<ObjectNode> updater);

    void markState(String statusReference, String state, Instant now);

    void markReady(String statusReference, Path manifest, boolean sourceDeleted, Instant now);

    void markError(String statusReference, String state, ExtractionException exception, Instant now);

    void writeManifest(Path path, FrameManifest manifest);

    FrameManifest readManifest(Path path);

    void moveDirectory(Path source, Path destination);

    String sha256(Path path);

    void delete(Path path);

    void deleteTree(Path path);

    Path tasksRoot();
}
