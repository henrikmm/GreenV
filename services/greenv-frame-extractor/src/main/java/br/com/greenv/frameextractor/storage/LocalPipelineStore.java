package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameManifest;
import br.com.greenv.frameextractor.port.SegmentObjectStorage;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "local", matchIfMissing = true)
public class LocalPipelineStore implements SegmentObjectStorage, LegacyPipelineStore {

    private final ObjectMapper objectMapper;
    private final Path root;
    private final Map<Path, Object> locks = new ConcurrentHashMap<>();

    public LocalPipelineStore(ObjectMapper objectMapper, ExtractorProperties properties) throws IOException {
        this.objectMapper = objectMapper;
        this.root = properties.root().toAbsolutePath().normalize();
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

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolveObjectKey(objectKey));
    }

    @Override
    public ObjectDescriptor download(String objectKey, Path destination) {
        Path source = resolveObjectKey(objectKey);
        if (!Files.isRegularFile(source)) {
            throw new ExtractionException("object_missing", "object is not available: " + objectKey, true);
        }
        try {
            Files.createDirectories(destination.toAbsolutePath().normalize().getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return descriptor(objectKey, destination);
        } catch (IOException exception) {
            throw new ExtractionException("object_download_failed", "could not materialize " + objectKey, true, exception);
        }
    }

    @Override
    public ObjectDescriptor putFile(String objectKey, Path source) {
        Path destination = resolveObjectKey(objectKey);
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(
                    destination.getParent(), destination.getFileName().toString(), ".publishing");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                move(temporary, destination, true);
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
            return descriptor(objectKey, destination);
        } catch (IOException exception) {
            throw new ExtractionException("object_publish_failed", "could not publish " + objectKey, true, exception);
        }
    }

    @Override
    public ObjectDescriptor putJson(String objectKey, Object value) {
        Path destination = resolveObjectKey(objectKey);
        atomicWrite(destination, value);
        return descriptor(objectKey, destination);
    }

    @Override
    public <T> T readJson(String objectKey, Class<T> type) {
        return readJson(resolveObjectKey(objectKey), type);
    }

    @Override
    public ObjectDescriptor stat(String objectKey) {
        Path path = resolveObjectKey(objectKey);
        if (!Files.isRegularFile(path)) {
            throw new ExtractionException("object_missing", "object is not available: " + objectKey, true);
        }
        return descriptor(objectKey, path);
    }

    @Override
    public void delete(String objectKey) {
        delete(resolveObjectKey(objectKey));
    }

    private Path resolveObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains(":")) {
            throw new ExtractionException("invalid_object_key", "storage object key is invalid", false);
        }
        Path relative = Path.of(objectKey).normalize();
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || !resolved.startsWith(root)) {
            throw new ExtractionException("object_key_escape", "storage object key escapes its namespace", false);
        }
        return resolved;
    }

    private ObjectDescriptor descriptor(String objectKey, Path path) {
        try {
            return new ObjectDescriptor(objectKey, sha256(path), Files.size(path));
        } catch (IOException exception) {
            throw new ExtractionException("artifact_stat_failed", "could not inspect " + objectKey, true, exception);
        }
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

    public void markError(String statusUri, String state, ExtractionException exception, Instant now) {
        updateStatus(statusUri, document -> {
            document.put("state", state);
            document.put("updatedAt", now.toString());
            document.put("errorCode", exception.code());
            document.put("errorMessage", exception.getMessage());
        });
    }

    public void writeManifest(Path path, FrameManifest manifest) {
        atomicWrite(path, manifest);
    }

    public void writeJson(Path path, Object value) {
        atomicWrite(path, value);
    }

    public <T> T readJson(Path path, Class<T> type) {
        try {
            return objectMapper.readValue(path.toFile(), type);
        } catch (JacksonException exception) {
            throw new ExtractionException(
                    "metadata_read_failed",
                    "could not read " + path.getFileName(),
                    false,
                    exception);
        }
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
