package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.port.SegmentObjectStorage.ObjectDescriptor;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class CloudStorageSupport {

    private static final int BUFFER_BYTES = 1024 * 1024;

    private CloudStorageSupport() {
    }

    static String requireObjectKey(String objectKey) {
        if (objectKey == null
                || objectKey.isBlank()
                || objectKey.startsWith("/")
                || objectKey.contains("\\")
                || objectKey.contains(":")
                || java.util.Arrays.stream(objectKey.split("/", -1))
                        .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) {
            throw new ExtractionException("invalid_object_key", "storage object key is invalid", false);
        }
        return objectKey;
    }

    static ObjectDescriptor descriptor(String objectKey, Path path) {
        try {
            return new ObjectDescriptor(objectKey, sha256(path), Files.size(path));
        } catch (IOException exception) {
            throw new ExtractionException("artifact_stat_failed", "could not inspect " + objectKey, true, exception);
        }
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[BUFFER_BYTES];
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

    static Path temporaryJsonFile() throws IOException {
        return Files.createTempFile("greenv-manifest-", ".json");
    }

    static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The operating system can reclaim an abandoned staging file.
        }
    }
}
