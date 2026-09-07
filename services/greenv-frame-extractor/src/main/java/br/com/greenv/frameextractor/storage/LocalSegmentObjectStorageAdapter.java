package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.port.SegmentObjectStorage;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "local", matchIfMissing = true)
public class LocalSegmentObjectStorageAdapter implements SegmentObjectStorage {

    private static final int CHECKSUM_BUFFER_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final Path root;

    public LocalSegmentObjectStorageAdapter(ObjectMapper objectMapper, ExtractorProperties extractorProperties)
            throws IOException {
        this.objectMapper = objectMapper;
        this.root = extractorProperties.root().toAbsolutePath().normalize();
        Files.createDirectories(root);
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
                move(temporary, destination);
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
        Path temporary = null;
        try {
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(
                    destination.getParent(), destination.getFileName().toString(), ".publishing");
            objectMapper.writeValue(temporary.toFile(), value);
            move(temporary, destination);
            return descriptor(objectKey, destination);
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new ExtractionException("object_publish_failed", "could not publish " + objectKey, true, exception);
        }
    }

    @Override
    public <T> T readJson(String objectKey, Class<T> type) {
        Path source = resolveObjectKey(objectKey);
        try {
            return objectMapper.readValue(source.toFile(), type);
        } catch (JacksonException exception) {
            throw new ExtractionException("metadata_read_failed", "could not read " + objectKey, false, exception);
        }
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
        try {
            Files.deleteIfExists(resolveObjectKey(objectKey));
        } catch (IOException exception) {
            throw new ExtractionException("object_delete_failed", "could not delete " + objectKey, true, exception);
        }
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

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[CHECKSUM_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A later workspace/storage sweep removes an abandoned temporary file.
        }
    }
}
