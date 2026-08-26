package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.ProcessingWorkspace;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class LocalProcessingWorkspace implements ProcessingWorkspace {

    private final Path root;

    public LocalProcessingWorkspace(ExtractorProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize().resolve(".workspaces");
    }

    @Override
    public Path create(SegmentExtractionRequest request) {
        Path workspace = root
                .resolve(request.sessionId().toString())
                .resolve("%08d-%d".formatted(request.segmentIndex(), request.attempt()))
                .normalize();
        if (!workspace.startsWith(root)) {
            throw new ExtractionException("workspace_escape", "processing workspace escapes its root", false);
        }
        clean(workspace);
        try {
            Files.createDirectories(workspace);
            return workspace;
        } catch (IOException exception) {
            throw new ExtractionException("workspace_create_failed", "could not create processing workspace", true, exception);
        }
    }

    @Override
    public void clean(Path workspace) {
        Path normalized = workspace.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root) || !Files.exists(normalized)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException exception) {
            throw new ExtractionException("workspace_cleanup_failed", "could not clean processing workspace", true, exception);
        }
    }
}
