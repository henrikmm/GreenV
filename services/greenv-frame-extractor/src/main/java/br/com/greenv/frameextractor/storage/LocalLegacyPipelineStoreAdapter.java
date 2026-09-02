package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.config.ConditionalOnLocalPipeline;
import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameManifest;
import br.com.greenv.frameextractor.port.LegacyPipelineStore;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnLocalPipeline
public class LocalLegacyPipelineStoreAdapter implements LegacyPipelineStore {

    private final ObjectMapper objectMapper;
    private final Path root;
    private final Map<Path, Object> locks = new ConcurrentHashMap<>();

    public LocalLegacyPipelineStoreAdapter(ObjectMapper objectMapper, ExtractorProperties extractorProperties)
            throws IOException {
        this.objectMapper = objectMapper;
        this.root = extractorProperties.root().toAbsolutePath().normalize();
        Files.createDirectories(root);
        for (String state : java.util.List.of("pending", "processing", "done", "failed")) {
            Files.createDirectories(root.resolve("tasks").resolve(state));
        }
    }

    public Path resolve(String uri) {
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException exception) {
            throw new ExtractionException("invalid_storage_uri", "storage URI is invalid", false, exception);
        }
        if (!"file".equalsIgnoreCase(parsed.getScheme())) {
            throw new ExtractionException("unsupported_storage_uri", "local worker accepts file URIs only", false);
        }
        Path path = Path.of(parsed).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new ExtractionException("storage_path_escape", "storage URI escapes the pipeline root", false);
        }
        return path;
    }

    public ObjectNode readStatus(String statusUri) {
        Path status = resolve(statusUri);
        synchronized (lock(status)) {
            try {
                JsonNode node = objectMapper.readTree(status.toFile());
                if (!(node instanceof ObjectNode object)) {
                    throw new ExtractionException("invalid_status", "job status is not a JSON object", false);
                }
                return object;
            } catch (JacksonException exception) {
                throw new ExtractionException("status_read_failed", "could not read job status", true, exception);
            }
        }
    }

    public void updateStatus(String statusUri, Consumer<ObjectNode> updater) {
        Path status = resolve(statusUri);
        synchronized (lock(status)) {
            ObjectNode document = readStatus(statusUri);
            updater.accept(document);
            atomicWrite(status, document);
        }
    }

    public void markState(String statusUri, String state, Instant now) {
        updateStatus(statusUri, document -> {
            document.put("state", state);
            document.put("updatedAt", now.toString());
            document.putNull("errorCode");
            document.putNull("errorMessage");
        });
    }

    public void markReady(String statusUri, Path manifest, boolean sourceDeleted, Instant now) {
        updateStatus(statusUri, document -> {
            document.put("state", "frames_ready");
            document.put("manifestUri", manifest.toUri().toString());
            document.put("sourceDeleted", sourceDeleted);
            document.put("updatedAt", now.toString());
            document.putNull("errorCode");
            document.putNull("errorMessage");
        });
    }

    public void markError(
            String statusUri,
            String state,
            String errorCode,
            String errorMessage,
            Instant now) {
        updateStatus(statusUri, document -> {
            document.put("state", state);
            document.put("updatedAt", now.toString());
            document.put("errorCode", errorCode);
            document.put("errorMessage", errorMessage);
        });
    }

    public void writeManifest(Path path, FrameManifest manifest) {
        atomicWrite(path, manifest);
    }

    public FrameManifest readManifest(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), FrameManifest.class);
        } catch (JacksonException exception) {
            throw new ExtractionException("manifest_read_failed", "could not read frame manifest", true, exception);
        }
    }

    public void moveDirectory(Path source, Path destination) {
        deleteTree(destination);
        try {
            move(source, destination, false);
        } catch (IOException exception) {
            throw new ExtractionException("publish_failed", "could not publish extracted frames", true, exception);
        }
    }

    public String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ExtractionException("checksum_failed", "could not checksum " + path.getFileName(), true, exception);
        }
    }

    public void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new ExtractionException("source_cleanup_failed", "could not delete source video", true, exception);
        }
    }

    public void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException exception) {
            throw new ExtractionException("cleanup_failed", "could not clean temporary output", true, exception);
        }
    }

    public Path tasksRoot() {
        return root.resolve("tasks");
    }

    private Object lock(Path path) {
        return locks.computeIfAbsent(path, ignored -> new Object());
    }

    private void atomicWrite(Path destination, Object value) {
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
            objectMapper.writeValue(temporary.toFile(), value);
            move(temporary, destination, true);
        } catch (IOException exception) {
            throw new ExtractionException("metadata_write_failed", "could not publish pipeline metadata", true, exception);
        }
    }

    private static void move(Path source, Path destination, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination);
            }
        }
    }
}
