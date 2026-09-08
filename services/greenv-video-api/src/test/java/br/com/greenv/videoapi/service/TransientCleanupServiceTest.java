package br.com.greenv.videoapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.JobDocument;
import br.com.greenv.videoapi.domain.Retention;
import br.com.greenv.videoapi.domain.SamplingOptions;
import br.com.greenv.videoapi.storage.LocalLegacyJobStoreAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TransientCleanupServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesOnlyExpiredTransientJobs() throws Exception {
        PipelineProperties properties = new PipelineProperties(
                temporaryDirectory.resolve("pipeline"),
                temporaryDirectory.resolve("saved"),
                1024,
                3);
        LocalLegacyJobStoreAdapter store = new LocalLegacyJobStoreAdapter(
                JsonMapper.builder().findAndAddModules().build(),
                properties);
        Instant old = Instant.parse("2026-08-01T12:00:00Z");
        UUID transientId = UUID.randomUUID();
        UUID savedId = UUID.randomUUID();
        SamplingOptions sampling = new SamplingOptions(10, 100, 1024);
        store.create(JobDocument.create(
                transientId, "transient.mp4", "video/mp4", 10, sampling, old));
        store.create(JobDocument.create(
                        savedId, "saved.mp4", "video/mp4", 10, sampling, old)
                .withRetention(Retention.SAVED, old));

        new TransientCleanupService(
                        store,
                        properties,
                        Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC))
                .removeExpiredJobs();

        assertThat(Files.exists(store.jobDirectory(transientId))).isFalse();
        assertThat(Files.isRegularFile(store.statusPath(savedId))).isTrue();
    }
}

