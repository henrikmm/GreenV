package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "local", matchIfMissing = true)
public class LocalCaptureObjectStorageAdapter implements CaptureObjectStorage {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final Path objectStorageRoot;

    public LocalCaptureObjectStorageAdapter(PipelineProperties pipelineProperties) {
        this.objectStorageRoot = pipelineProperties.root().toAbsolutePath().normalize().resolve("capture-sessions");
    }

    @Override
    public StoredObject put(
            String objectKey,
            InputStream input,
            String expectedSha256,
            long maximumBytes) {
        requireSha256(expectedSha256);
        Path destination = resolve(objectKey);
        Path directory = destination.getParent();
        try {
            Files.createDirectories(directory);
            if (Files.isRegularFile(destination)) {
                String existing = sha256(destination);
                if (!existing.equals(expectedSha256)) {
                    throw new ApplicationException(
                            FailureKind.CONFLICT,
                            "segment_object_conflict",
                            objectKey + " already exists with a different checksum");
                }
                return new StoredObject(objectKey, existing, Files.size(destination));
            }

            Path temporary = Files.createTempFile(directory, destination.getFileName().toString(), ".uploading");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long bytes = copyBounded(input, temporary, digest, maximumBytes);
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(expectedSha256)) {
                    throw new ApplicationException(
                            FailureKind.INVALID_INPUT,
                            "checksum_mismatch",
                            objectKey + " checksum does not match X-Content-SHA256");
                }
                move(temporary, destination);
                return new StoredObject(objectKey, actual, bytes);
            } catch (RuntimeException | IOException | NoSuchAlgorithmException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
        } catch (ApplicationException exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR,
                    "capture_storage_failure",
                    "could not store " + objectKey + ": " + exception.getMessage());
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    @Override
    public byte[] read(String objectKey, long maximumBytes) {
        Path path = resolve(objectKey);
        try {
            if (!Files.isRegularFile(path)) {
                throw new ApplicationException(
                        FailureKind.NOT_FOUND, "capture_object_not_found", "capture object does not exist");
            }
            long bytes = Files.size(path);
            if (bytes > maximumBytes) {
                throw new ApplicationException(
                        FailureKind.INTERNAL_ERROR,
                        "capture_object_too_large",
                        "capture object exceeds its read limit");
            }
            return Files.readAllBytes(path);
        } catch (ApplicationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR,
                    "capture_storage_failure",
                    "could not read capture object");
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains(":")) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_object_key", "storage object key is invalid");
        }
        Path relative = Path.of(objectKey).normalize();
        Path path = objectStorageRoot.getParent().resolve(relative).toAbsolutePath().normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || !path.startsWith(objectStorageRoot.getParent())) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_object_key", "storage object key escapes its namespace");
        }
        return path;
    }

    private static long copyBounded(
            InputStream input,
            Path destination,
            MessageDigest digest,
            long maximumBytes) throws IOException {
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
                if (total > maximumBytes) {
                    throw new ApplicationException(
                            FailureKind.PAYLOAD_TOO_LARGE,
                            "segment_object_too_large",
                            "capture object exceeds its configured limit");
                }
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "empty_segment_object", "capture object is empty");
        }
        return total;
    }

    private static void requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
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

}
