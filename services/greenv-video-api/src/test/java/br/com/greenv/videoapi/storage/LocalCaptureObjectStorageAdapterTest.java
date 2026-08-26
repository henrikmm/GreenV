package br.com.greenv.videoapi.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.config.PipelineProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCaptureObjectStorageAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOpaqueKeysAndRejectsProviderUrisAndTraversal() throws Exception {
        LocalCaptureObjectStorageAdapter storage = new LocalCaptureObjectStorageAdapter(new PipelineProperties(
                temporaryDirectory.resolve("pipeline"),
                temporaryDirectory.resolve("saved"),
                1024,
                3));
        byte[] value = "capture".getBytes(StandardCharsets.UTF_8);
        String key = "capture-sessions/session/segments/00000000/source.mp4";

        var stored = storage.put(key, new ByteArrayInputStream(value), sha256(value), 1024);

        assertThat(stored.objectKey()).isEqualTo(key);
        assertThat(storage.read(key, 1024)).isEqualTo(value);
        assertThatThrownBy(() -> storage.exists("file:///tmp/source.mp4"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("object key is invalid");
        assertThatThrownBy(() -> storage.exists("../outside/source.mp4"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("escapes");
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
