package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.storage.LocalPipelineStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ExtractionServiceIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesVerifiedFramesThenDeletesTheTransientSource() throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg is not installed");
        assumeTrue(commandExists("ffprobe"), "ffprobe is not installed");

        Path root = temporaryDirectory.resolve("pipeline").toAbsolutePath();
        ExtractorProperties properties = new ExtractorProperties(
                root, "ffmpeg", "ffprobe", 300, 3, 1000, false);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        LocalPipelineStore store = new LocalPipelineStore(objectMapper, properties);
        CommandRunner commandRunner = new CommandRunner();
        ExtractionService service = new ExtractionService(
                properties,
                store,
                new MediaProbe(commandRunner, objectMapper, properties),
                new SamplingPlanner(),
                new FfmpegExtractor(commandRunner, properties),
                Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC));

        UUID jobId = UUID.randomUUID();
        Path jobRoot = root.resolve("jobs").resolve(jobId.toString());
        Path source = jobRoot.resolve("source/video.mp4");
        Path status = jobRoot.resolve("status.json");
        Files.createDirectories(source.getParent());
        generateVideo(source);
        Files.createDirectories(status.getParent());
        objectMapper.writeValue(status, Map.of(
                "schemaVersion", 1,
                "jobId", jobId.toString(),
                "state", "queued",
                "updatedAt", "2026-08-16T12:00:00Z"));

        String generation = store.sha256(source);
        FrameExtractionRequest request = new FrameExtractionRequest(
                1,
                0,
                jobId,
                jobId + ":" + generation,
                source.toUri().toString(),
                status.toUri().toString(),
                jobRoot.toUri().toString(),
                generation,
                2,
                100,
                1024,
                Instant.parse("2026-08-16T12:00:00Z"));

        var manifest = service.extract(request);

        assertThat(manifest.sampling().count()).isEqualTo(4);
        assertThat(manifest.frames()).hasSize(4);
        assertThat(manifest.frames()).allSatisfy(frame -> {
            assertThat(frame.sha256()).hasSize(64);
            assertThat(Files.isRegularFile(jobRoot.resolve("frames").resolve(frame.fileName()))).isTrue();
        });
        assertThat(Files.isRegularFile(jobRoot.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(source)).isFalse();
        assertThat(store.readStatus(status.toUri().toString()).path("state").asString())
                .isEqualTo("frames_ready");

        var redelivered = service.extract(request);
        assertThat(redelivered.sourceGeneration()).isEqualTo(generation);
        assertThat(redelivered.frames()).hasSize(4);
    }

    private static void generateVideo(Path destination) {
        var result = new CommandRunner().run(List.of(
                "ffmpeg",
                "-nostdin",
                "-v", "error",
                "-y",
                "-f", "lavfi",
                "-i", "testsrc=size=320x180:rate=10",
                "-frames:v", "20",
                "-c:v", "mpeg4",
                destination.toString()), java.time.Duration.ofMinutes(1));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
