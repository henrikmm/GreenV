package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
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
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_object_key", "storage object key is invalid");
        }
        return objectKey;
    }

    static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "invalid_checksum",
                    "X-Content-SHA256 must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    static StagedUpload stage(InputStream input, String expectedSha256, long maximumBytes) {
        requireSha256(expectedSha256);
        Path path = null;
        try {
            path = Files.createTempFile("greenv-capture-", ".uploading");
            MessageDigest digest = digest();
            long bytes = 0;
            byte[] buffer = new byte[BUFFER_BYTES];
            try (DigestOutputStream output = new DigestOutputStream(Files.newOutputStream(path), digest)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes += read;
                    if (bytes > maximumBytes) {
                        throw new ApplicationException(
                                FailureKind.PAYLOAD_TOO_LARGE,
                                "segment_object_too_large",
                                "capture object exceeds its configured limit");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (bytes == 0) {
                throw new ApplicationException(
                        FailureKind.INVALID_INPUT, "empty_segment_object", "capture object is empty");
            }
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (!actualSha256.equals(expectedSha256)) {
                throw new ApplicationException(
                        FailureKind.INVALID_INPUT,
                        "checksum_mismatch",
                        "capture object checksum does not match X-Content-SHA256");
            }
            return new StagedUpload(path, actualSha256, bytes);
        } catch (ApplicationException exception) {
            deleteQuietly(path);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(path);
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR,
                    "capture_staging_failure",
                    "could not stage capture object for upload");
        }
    }

    static byte[] readBounded(InputStream input, long maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new ApplicationException(
                        FailureKind.INTERNAL_ERROR,
                        "capture_object_too_large",
                        "capture object exceeds its read limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The operating system can reclaim an abandoned staging file.
        }
    }

    record StagedUpload(Path path, String sha256, long bytes) implements AutoCloseable {

        @Override
        public void close() {
            deleteQuietly(path);
        }
    }
}
