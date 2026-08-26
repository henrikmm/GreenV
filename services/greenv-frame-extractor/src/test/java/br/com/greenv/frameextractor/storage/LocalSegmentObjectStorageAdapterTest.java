package br.com.greenv.frameextractor.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class LocalSegmentObjectStorageAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesAndReadsAnOpaqueObjectKey() throws Exception {
        LocalSegmentObjectStorageAdapter storage = storage();
        Path source = temporaryDirectory.resolve("source.bin");
        Files.writeString(source, "artifact");

        var stored = storage.putFile("capture-sessions/session/segments/00000000/source.mp4", source);

        assertThat(stored.objectKey()).isEqualTo("capture-sessions/session/segments/00000000/source.mp4");
        assertThat(stored.sha256()).hasSize(64);
        assertThat(storage.exists(stored.objectKey())).isTrue();
    }

    @Test
    void rejectsProviderUrisAndTraversal() throws Exception {
        LocalSegmentObjectStorageAdapter storage = storage();

        assertThatThrownBy(() -> storage.exists("file:///tmp/source.mp4"))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("object key is invalid");
        assertThatThrownBy(() -> storage.exists("../outside/source.mp4"))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("escapes");
    }

    private LocalSegmentObjectStorageAdapter storage() throws Exception {
        return new LocalSegmentObjectStorageAdapter(
                JsonMapper.builder().findAndAddModules().build(),
                new ExtractorProperties(
                        temporaryDirectory.resolve("pipeline"),
                        "ffmpeg",
                        "ffprobe",
                        300,
                        3,
                        1000,
                        false));
    }
}
