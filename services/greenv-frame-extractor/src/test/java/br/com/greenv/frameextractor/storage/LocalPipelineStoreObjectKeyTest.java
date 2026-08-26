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

class LocalPipelineStoreObjectKeyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesAndReadsAnOpaqueObjectKey() throws Exception {
        LocalPipelineStore store = store();
        Path source = temporaryDirectory.resolve("source.bin");
        Files.writeString(source, "artifact");

        var stored = store.putFile("capture-sessions/session/segments/00000000/source.mp4", source);

        assertThat(stored.objectKey()).isEqualTo("capture-sessions/session/segments/00000000/source.mp4");
        assertThat(stored.sha256()).hasSize(64);
        assertThat(store.exists(stored.objectKey())).isTrue();
    }

    @Test
    void rejectsProviderUrisAndTraversal() throws Exception {
        LocalPipelineStore store = store();

        assertThatThrownBy(() -> store.exists("file:///tmp/source.mp4"))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("object key is invalid");
        assertThatThrownBy(() -> store.exists("../outside/source.mp4"))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("escapes");
    }

    private LocalPipelineStore store() throws Exception {
        return new LocalPipelineStore(
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
