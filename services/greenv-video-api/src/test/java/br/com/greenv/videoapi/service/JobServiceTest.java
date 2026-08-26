package br.com.greenv.videoapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.FrameExtractionRequest;
import br.com.greenv.videoapi.domain.JobState;
import br.com.greenv.videoapi.domain.SamplingOptions;
import br.com.greenv.videoapi.storage.LocalLegacyJobStoreAdapter;
import br.com.greenv.videoapi.task.LocalFrameWorkQueueAdapter;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class JobServiceTest {

    @TempDir
    Path temporaryDirectory;

    private ObjectMapper objectMapper;
    private PipelineProperties properties;
    private LocalLegacyJobStoreAdapter store;
    private JobService service;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        properties = new PipelineProperties(
                temporaryDirectory.resolve("pipeline"),
                temporaryDirectory.resolve("saved"),
                1024 * 1024,
                3);
        store = new LocalLegacyJobStoreAdapter(objectMapper, properties);
        service = new JobService(
                store,
                new LocalFrameWorkQueueAdapter(objectMapper, properties),
                properties,
                Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void streamsUploadAndPublishesOneIdempotentTask() throws Exception {
        byte[] source = "synthetic video bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var job = service.create(
                "../road.mp4",
                "video/mp4",
                source.length,
                new SamplingOptions(10.0, 100, 1024));

        assertThat(job.fileName()).isEqualTo("road.mp4");
        assertThat(job.state()).isEqualTo(JobState.CREATED);

        var uploaded = service.upload(job.jobId(), new ByteArrayInputStream(source));
        assertThat(uploaded.state()).isEqualTo(JobState.UPLOADING);
        assertThat(uploaded.actualSizeBytes()).isEqualTo((long) source.length);
        assertThat(uploaded.sourceGeneration()).hasSize(64);
        assertThat(Files.readAllBytes(store.sourcePath(job.jobId()))).isEqualTo(source);

        var queued = service.complete(job.jobId());
        assertThat(queued.state()).isEqualTo(JobState.QUEUED);
        service.complete(job.jobId());

        try (var tasks = Files.list(store.pendingTasksDirectory())) {
            var files = tasks.toList();
            assertThat(files).hasSize(1);
            FrameExtractionRequest request = objectMapper.readValue(files.getFirst(), FrameExtractionRequest.class);
            assertThat(request.jobId()).isEqualTo(job.jobId());
            assertThat(request.sourceGeneration()).isEqualTo(uploaded.sourceGeneration());
            assertThat(request.requestedFps()).isEqualTo(10.0);
            assertThat(request.maxFrames()).isEqualTo(100);
        }
    }

    @Test
    void rejectsUploadWhoseByteCountDiffersFromDeclaration() {
        var job = service.create(
                "road.mp4",
                "video/mp4",
                20L,
                new SamplingOptions(
                        SamplingOptions.DEFAULT_FPS,
                        SamplingOptions.DEFAULT_MAX_FRAMES,
                        SamplingOptions.DEFAULT_LONG_EDGE));

        assertThatThrownBy(() -> service.upload(
                        job.jobId(),
                        new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(FailureKind.INVALID_INPUT);
                    assertThat(exception.code()).isEqualTo("size_mismatch");
                });
        assertThat(service.get(job.jobId()).state()).isEqualTo(JobState.FAILED);
        assertThat(Files.exists(store.sourcePath(job.jobId()))).isFalse();
    }
}

