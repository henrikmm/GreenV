package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.MediaProbeResult;
import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.MeasurementRequest;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.domain.SegmentTelemetry;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.storage.LocalSegmentObjectStorageAdapter;
import br.com.greenv.frameextractor.storage.LocalProcessingWorkspaceAdapter;
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
        LocalSegmentObjectStorageAdapter objects = new LocalSegmentObjectStorageAdapter(mapper, properties);
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
                prefix, telemetry.capturedAtUtc(), 1_000, Instant.parse("2026-08-25T12:00:01Z"),
                "SP-021", "norte");

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
        // Every finished segment must announce itself, or nothing downstream ever measures it.
        List<MeasurementRequest> announced = new ArrayList<>();
        SegmentExtractionService service = new SegmentExtractionService(
                properties,
                objects,
                new LocalProcessingWorkspaceAdapter(properties),
                segments,
                mediaProbe,
                timestamps,
                new TelemetryAssociator(),
                new SamplingPlanner(),
                ffmpeg,
                announced::add,
                Clock.fixed(Instant.parse("2026-08-25T12:00:02Z"), ZoneOffset.UTC));

        var first = service.extract(request);
        var redelivered = service.extract(request.nextAttempt());

        assertThat(first).isEqualTo(redelivered);
        assertThat(first.schemaVersion()).isEqualTo(2);
        assertThat(first.encodedFrameCount()).isEqualTo(2);
        // 10 fps across the probed one-second clip, under the 112-frame cap.
        assertThat(first.sampledFrames()).hasSize(10);
        assertThat(first.sourceDeleted()).isFalse();
        assertThat(first.frameMetadataObjectKey()).isEqualTo(prefix + "/frame-metadata-v2.json");
        // A redelivered segment announces itself again. The measurement worker is idempotent on
        // sourceGeneration, so a second announcement costs a manifest read and never a second
        // depth run — whereas a missed announcement leaves the segment unmeasured forever.
        assertThat(announced).hasSize(2);
        assertThat(announced).allSatisfy(announcement ->
                assertThat(announcement.outputPrefix()).isEqualTo(prefix));
        // The road reaches the artifact and the announcement alike. Without it the measurement
        // packet raises `road-metadata-missing` and cannot be joined to a trecho on the map.
        assertThat(first.rodovia()).isEqualTo("SP-021");
        assertThat(first.sentido()).isEqualTo("norte");
        assertThat(announced).allSatisfy(announcement -> {
            assertThat(announcement.rodovia()).isEqualTo("SP-021");
            assertThat(announcement.sentido()).isEqualTo("norte");
        });
        // The source segment survives extraction, so a later stage can re-sample it.
        assertThat(objects.exists(request.videoObjectKey())).isTrue();
        assertThat(segments.readyCount).isEqualTo(2);
    }

    @Test
    void announcesNoRoadForACaptureThatNeverCarriedOne() throws Exception {
        ExtractorProperties properties = new ExtractorProperties(
                temporaryDirectory.resolve("pipeline-absent"), "ffmpeg", "ffprobe", 300, 3, 1000, false);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        LocalSegmentObjectStorageAdapter objects = new LocalSegmentObjectStorageAdapter(mapper, properties);
        String prefix = "capture-sessions/1b1e1c6c-4f2e-4a1b-9a0d-2f4b6a8c0d1e/segments/00000000";
        Path source = temporaryDirectory.resolve("legacy.mp4");
        Files.writeString(source, "fake-encoded-video");
        var video = objects.putFile(prefix + "/source.mp4", source);
        SegmentTelemetry telemetry = new SegmentTelemetry(
                1,
                UUID.fromString("1b1e1c6c-4f2e-4a1b-9a0d-2f4b6a8c0d1e"),
                0,
                Instant.parse("2026-08-25T12:00:00Z"),
                1_000,
                "segment_anchor",
                List.of(),
                List.of());
        var telemetryObject = objects.putJson(prefix + "/telemetry.json", telemetry);
        // A segment queued before the capture app asked for a road: neither field is present.
        SegmentExtractionRequest request = new SegmentExtractionRequest(
                2, 0, telemetry.sessionId(), 0, "mobile:legacy:0",
                video.objectKey(), video.sha256(), telemetryObject.objectKey(), telemetryObject.sha256(),
                prefix, telemetry.capturedAtUtc(), 1_000, Instant.parse("2026-08-25T12:00:01Z"),
                null, null);

        List<MeasurementRequest> announced = new ArrayList<>();
        var first = service(properties, objects, announced).extract(request);

        assertThat(first.rodovia()).isNull();
        assertThat(first.sentido()).isNull();
        assertThat(announced).singleElement().satisfies(announcement -> {
            assertThat(announcement.rodovia()).isNull();
            assertThat(announcement.sentido()).isNull();
        });
    }

    @Test
    void refusesASentidoOutsideTheClosedVocabulary() {
        SegmentExtractionRequest request = new SegmentExtractionRequest(
                2, 0, UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f"), 0, "mobile:session:0",
                "capture-sessions/session/segments/00000000/source.mp4", "a".repeat(64),
                "capture-sessions/session/segments/00000000/telemetry.json", "b".repeat(64),
                "capture-sessions/session/segments/00000000",
                Instant.parse("2026-08-25T11:59:50Z"), 10_000, Instant.parse("2026-08-25T12:00:00Z"),
                "BR-101", "nordeste");

        // Terminal, not retryable: the same message will be just as unknown on every redelivery,
        // and a road nobody can name is worse in a packet than an announced failure.
        assertThatThrownBy(() -> service(null, null, new ArrayList<>()).extract(request))
                .isInstanceOf(ExtractionException.class)
                .satisfies(thrown -> {
                    assertThat(((ExtractionException) thrown).code()).isEqualTo("invalid_segment_request");
                    assertThat(((ExtractionException) thrown).retryable()).isFalse();
                });
    }

    /** The service with everything ffmpeg-shaped stubbed, so a test can drive it without ffmpeg. */
    private static SegmentExtractionService service(
            ExtractorProperties properties,
            LocalSegmentObjectStorageAdapter objects,
            List<MeasurementRequest> announced) throws Exception {
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
        return new SegmentExtractionService(
                properties,
                objects,
                properties == null ? null : new LocalProcessingWorkspaceAdapter(properties),
                new RecordingSegmentStore(),
                mediaProbe,
                timestamps,
                new TelemetryAssociator(),
                new SamplingPlanner(),
                ffmpeg,
                announced::add,
                Clock.fixed(Instant.parse("2026-08-25T12:00:02Z"), ZoneOffset.UTC));
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
        public void markError(
                SegmentExtractionRequest request,
                String errorCode,
                String errorMessage,
                Instant now) {
        }
    }
}
