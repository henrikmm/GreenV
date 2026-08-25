package br.com.greenv.frameextractor.task;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.storage.LocalPipelineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class LocalTaskInboxTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyClaimsAndRequeuesWithANewAttempt() throws Exception {
        Path root = temporaryDirectory.resolve("pipeline").toAbsolutePath();
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        LocalPipelineStore store = new LocalPipelineStore(
                objectMapper,
                new ExtractorProperties(root, "ffmpeg", "ffprobe", 300, 3, 1000, true));
        LocalTaskInbox inbox = new LocalTaskInbox(objectMapper, store);
        UUID jobId = UUID.randomUUID();
        FrameExtractionRequest request = new FrameExtractionRequest(
                1,
                0,
                jobId,
                "key",
                root.resolve("source").toUri().toString(),
                root.resolve("status.json").toUri().toString(),
                root.resolve("output").toUri().toString(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                10,
                100,
                1024,
                Instant.parse("2026-08-16T12:00:00Z"));
        Path pending = store.tasksRoot().resolve("pending").resolve(jobId + ".json");
        objectMapper.writeValue(pending, request);

        var claimed = inbox.claim().orElseThrow();
        assertThat(claimed.path().getParent().getFileName().toString()).isEqualTo("processing");
        assertThat(inbox.claim()).isEmpty();

        inbox.retry(claimed);
        var retried = inbox.claim().orElseThrow();
        assertThat(retried.request().attempt()).isEqualTo(1);
        inbox.complete(retried);

        assertThat(Files.list(store.tasksRoot().resolve("done")).toList()).hasSize(1);
    }
}
