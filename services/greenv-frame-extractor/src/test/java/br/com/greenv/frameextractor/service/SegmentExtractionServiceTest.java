package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.MediaProbeResult;
import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.domain.SegmentTelemetry;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.storage.LocalPipelineStore;
import br.com.greenv.frameextractor.storage.LocalProcessingWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SegmentExtractionServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesAndReusesACompleteObjectKeyGeneration() throws Exception {
        ExtractorProperties properties = new ExtractorProperties(
                temporaryDirectory.resolve("pipeline"), "ffmpeg", "ffprobe", 300, 3, 1000, false);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        LocalPipelineStore objects = new LocalPipelineStore(mapper, properties);
        String prefix = "capture-sessions/2d995d67-dd6f-4792-af22-480c43b37f2f/segments/00000000";
        Path source = temporaryDirectory.resolve("source.mp4");
        Files.writeString(source, "fake-encoded-video");
        var video = objects.putFile(prefix + "/source.mp4", source);
        SegmentTelemetry telemetry = new SegmentTelemetry(
                1,
                UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f"),
                0,
                Instant.parse("2026-08-25T12:00:00Z"),
                1_000,
                "segment_anchor",
                List.of(),
                List.of());
        var telemetryObject = objects.putJson(prefix + "/telemetry.json", telemetry);
        SegmentExtractionRequest request = new SegmentExtractionRequest(
                2, 0, telemetry.sessionId(), 0, "mobile:session:0",
                video.objectKey(), video.sha256(), telemetryObject.objectKey(), telemetryObject.sha256(),
                prefix, telemetry.capturedAtUtc(), 1_000, Instant.parse("2026-08-25T12:00:01Z"));

        MediaProbe mediaProbe = mock(MediaProbe.class);
        when(mediaProbe.probe(any())).thenReturn(new MediaProbeResult(1.0, 2.0, 320, 180, 0, 320, 180));
        FrameTimestampProbe timestamps = mock(FrameTimestampProbe.class);
        when(timestamps.probe(any())).thenReturn(List.of(
                new EncodedFrameTimestamp(0, 0, true),
                new EncodedFrameTimestamp(1, 500_000_000, false)));
        FfmpegExtractor ffmpeg = mock(FfmpegExtractor.class);
        when(ffmpeg.extract(any(), any(), any(), any())).thenAnswer(invocation -> {
            Path directory = invocation.getArgument(1);
            SamplingPlan sampling = invocation.getArgument(2);
            Files.createDirectories(directory);
            List<Path> frames = new ArrayList<>();
            for (int index = 1; index <= sampling.count(); index++) {
                Path frame = directory.resolve("frame-%04d.jpg".formatted(index));
                Files.writeString(frame, "jpeg-" + index);
                frames.add(frame);
            }
            return frames;
        });
        RecordingSegmentStore segments = new RecordingSegmentStore();
        SegmentExtractionService service = new SegmentExtractionService(
                properties,
                objects,
                new LocalProcessingWorkspace(properties),
                segments,
                mediaProbe,
                timestamps,
                new TelemetryAssociator(),
                new SamplingPlanner(),
                ffmpeg,
                Clock.fixed(Instant.parse("2026-08-25T12:00:02Z"), ZoneOffset.UTC));

        var first = service.extract(request);
        var redelivered = service.extract(request.nextAttempt());

        assertThat(first).isEqualTo(redelivered);
        assertThat(first.schemaVersion()).isEqualTo(2);
        assertThat(first.encodedFrameCount()).isEqualTo(2);
        assertThat(first.sampledFrames()).hasSize(2);
        assertThat(first.sourceDeleted()).isTrue();
        assertThat(first.frameMetadataObjectKey()).isEqualTo(prefix + "/frame-metadata-v2.json");
        assertThat(objects.exists(request.videoObjectKey())).isFalse();
        assertThat(segments.readyCount).isEqualTo(2);
    }

    private static final class RecordingSegmentStore implements CaptureSegmentStore {
        private int readyCount;

        @Override
        public String state(SegmentExtractionRequest request) {
            return "queued";
        }

        @Override
        public void markValidating(SegmentExtractionRequest request, Instant now) {
        }

        @Override
        public void markReady(SegmentExtractionRequest request, String key, int frameCount, Instant now) {
            readyCount++;
        }

        @Override
        public void markError(SegmentExtractionRequest request, ExtractionException exception, Instant now) {
        }
    }
}
