package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.api.ApiException;
import br.com.greenv.videoapi.config.CaptureProperties;
import br.com.greenv.videoapi.config.PipelineProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CaptureObjectStore {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final Path root;
    private final CaptureProperties properties;

    public CaptureObjectStore(PipelineProperties pipeline, CaptureProperties properties) {
        this.root = pipeline.root().toAbsolutePath().normalize().resolve("capture-sessions");
        this.properties = properties;
    }

    public StoredObject storeVideo(
            UUID sessionId,
            int segmentIndex,
            InputStream input,
            String expectedSha256) {
        return store(sessionId, segmentIndex, "source.mp4", input, expectedSha256, properties.maxSegmentBytes());
    }

    public StoredObject storeTelemetry(
            UUID sessionId,
            int segmentIndex,
            InputStream input,
            String expectedSha256) {
        return store(sessionId, segmentIndex, "telemetry.json", input, expectedSha256, properties.maxTelemetryBytes());
    }

    public Path segmentDirectory(UUID sessionId, int segmentIndex) {
        if (segmentIndex < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_segment_index", "segment index must be non-negative");
        }
        Path path = root.resolve(sessionId.toString()).resolve("segments").resolve("%08d".formatted(segmentIndex));
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_segment_path", "segment path escapes capture storage");
        }
        return normalized;
    }

    public Path manifestPath(UUID sessionId, int segmentIndex) {
        return segmentDirectory(sessionId, segmentIndex).resolve("segment-manifest-v1.json");
    }

    private StoredObject store(
            UUID sessionId,
            int segmentIndex,
            String name,
            InputStream input,
            String expectedSha256,
            long limit) {
        requireSha256(expectedSha256);
        Path directory = segmentDirectory(sessionId, segmentIndex);
        Path destination = directory.resolve(name);
        try {
            Files.createDirectories(directory);
            if (Files.isRegularFile(destination)) {
                String existing = sha256(destination);
                if (!existing.equals(expectedSha256)) {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "segment_object_conflict",
                            name + " already exists with a different checksum");
                }
                return new StoredObject(destination.toUri().toString(), existing, Files.size(destination));
            }

            Path temporary = Files.createTempFile(directory, name, ".uploading");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long bytes = copyBounded(input, temporary, digest, limit);
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(expectedSha256)) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "checksum_mismatch",
                            name + " checksum does not match X-Content-SHA256");
                }
                move(temporary, destination);
                return new StoredObject(destination.toUri().toString(), actual, bytes);
            } catch (RuntimeException | IOException | NoSuchAlgorithmException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "capture_storage_failure",
                    "could not store " + name + ": " + exception.getMessage());
        }
    }

    private static long copyBounded(
            InputStream input,
            Path destination,
            MessageDigest digest,
            long limit) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (DigestOutputStream output = new DigestOutputStream(
                Files.newOutputStream(
                        destination,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING),
                digest)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new ApiException(
                            HttpStatus.CONTENT_TOO_LARGE,
                            "segment_object_too_large",
                            "capture object exceeds its configured limit");
                }
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_segment_object", "capture object is empty");
        }
        return total;
    }

    private static void requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_checksum",
                    "X-Content-SHA256 must be 64 lowercase hexadecimal characters");
        }
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    public record StoredObject(String uri, String sha256, long bytes) {
    }
}
