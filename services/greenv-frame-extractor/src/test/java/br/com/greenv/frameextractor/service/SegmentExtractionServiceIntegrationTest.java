package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.domain.LocationSample;
import br.com.greenv.frameextractor.domain.SegmentManifest;
import br.com.greenv.frameextractor.domain.SegmentTelemetry;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.storage.LocalSegmentObjectStorageAdapter;
import br.com.greenv.frameextractor.storage.LocalProcessingWorkspaceAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SegmentExtractionServiceIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void materializesFromObjectStorageAndPublishesProviderNeutralArtifacts() throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg is not installed");
        assumeTrue(commandExists("ffprobe"), "ffprobe is not installed");

        Path root = temporaryDirectory.resolve("pipeline");
        ExtractorProperties properties = new ExtractorProperties(root, "ffmpeg", "ffprobe", 300, 3, 1000, false);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        LocalSegmentObjectStorageAdapter objects = new LocalSegmentObjectStorageAdapter(mapper, properties);
        Path source = temporaryDirectory.resolve("input.mp4");
        generateVideo(source);
        String prefix = "capture-sessions/2d995d67-dd6f-4792-af22-480c43b37f2f/segments/00000000";
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
                2,
                0,
                telemetry.sessionId(),
                0,
                "mobile:session:0",
                video.objectKey(),
                video.sha256(),
                telemetryObject.objectKey(),
                telemetryObject.sha256(),
                prefix,
                telemetry.capturedAtUtc(),
                1_000,
                Instant.parse("2026-08-25T12:00:01Z"));
        RecordingSegmentStore segments = new RecordingSegmentStore();
        CommandRunner runner = new CommandRunner();
        SegmentExtractionService service = new SegmentExtractionService(
                properties,
                objects,
                new LocalProcessingWorkspaceAdapter(properties),
                segments,
                new MediaProbe(runner, mapper, properties),
                new FrameTimestampProbe(runner, mapper, properties),
                new TelemetryAssociator(),
                new SamplingPlanner(),
                new GroupPlanner(),
                new FfmpegExtractor(runner, properties),
                Clock.fixed(Instant.parse("2026-08-25T12:00:02Z"), ZoneOffset.UTC));

        var manifest = service.extract(request);

        assertThat(manifest.schemaVersion()).isEqualTo(2);
        assertThat(manifest.frameMetadataObjectKey()).isEqualTo(prefix + "/frame-metadata-v2.json");
        assertThat(manifest.frameMetadataObjectKey()).doesNotContain("://");
        assertThat(manifest.sourceDeleted()).isFalse();
        assertThat(objects.exists(request.videoObjectKey())).isTrue();
        assertThat(objects.exists(prefix + "/segment-manifest-v2.json")).isTrue();
        assertThat(segments.manifestObjectKey).isEqualTo(prefix + "/segment-manifest-v2.json");
    }


    /**
     * The case a laptop webcam produces: telemetry arrives, and it says the camera never moved.
     * Publishing frames would spend storage and a GPU run on many views of one viewpoint, which
     * cannot reconstruct anything - but everything needed to understand the capture is still
     * written, including the measured frame rate.
     */
    @Test
    void refusesToPublishFramesForACameraThatDidNotMove() throws Exception {
        var result = run(stationaryFixes());

        assertThat(result.manifest.samplingStrategy()).isEqualTo("insufficient-motion");
        assertThat(result.manifest.sampledFrames()).isEmpty();
        assertThat(result.manifest.groups()).isEmpty();

        // Still measured and still recorded: this is how a device's real frame rate is checked.
        assertThat(result.manifest.nativeFps()).isGreaterThan(0);
        assertThat(result.manifest.encodedFrameCount()).isPositive();
        assertThat(result.objects.exists(result.prefix + "/frame-metadata-v2.json")).isTrue();
        assertThat(result.objects.exists(result.prefix + "/segment-manifest-v2.json")).isTrue();
        // The source is kept, so a segment refused here can be re-sampled if the rule is wrong.
        assertThat(result.objects.exists(result.prefix + "/source.mp4")).isTrue();
    }

    @Test
    void cutsAMovingCaptureIntoStretchesAndPublishesThem() throws Exception {
        var result = run(drivingFixes());

        assertThat(result.manifest.samplingStrategy()).isEqualTo("distance-groups");
        assertThat(result.manifest.groups()).isNotEmpty();
        assertThat(result.manifest.sampledFrames()).isNotEmpty();
        assertThat(result.manifest.pathMeters()).isGreaterThan(result.manifest.netDisplacementMeters() * 0.9);
        assertThat(result.manifest.sampledFrames())
                .allSatisfy(frame -> assertThat(frame.sourceFrameIndex()).isNotNegative());
        assertThat(result.manifest.groups())
                .allSatisfy(group -> assertThat(group.frameCount()).isGreaterThanOrEqualTo(2));
    }

    /** A stationary camera: every fix at the same place, as a webcam on a desk reports. */
    private static List<LocationSample> stationaryFixes() {
        List<LocationSample> fixes = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fixes.add(fix(i, -23.5, -46.6, 0.0));
        }
        return fixes;
    }

    /** Driving north at 10 m/s, which is 36 km/h. */
    private static List<LocationSample> drivingFixes() {
        List<LocationSample> fixes = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fixes.add(fix(i, -23.5 + (i * 10.0) / 111_320.0, -46.6, 10.0));
        }
        return fixes;
    }

    private static LocationSample fix(int second, double latitude, double longitude, double speed) {
        return new LocationSample(
                1_000L + second * 1_000_000_000L, latitude, longitude, null, 5.0, null, speed,
                null, null, null, null);
    }

    private record Extraction(
            SegmentManifest manifest, LocalSegmentObjectStorageAdapter objects, String prefix) {}

    private Extraction run(List<LocationSample> locations) throws Exception {
        assumeTrue(commandExists("ffmpeg"), "ffmpeg is not installed");
        assumeTrue(commandExists("ffprobe"), "ffprobe is not installed");

        Path root = temporaryDirectory.resolve("pipeline-" + locations.hashCode());
        ExtractorProperties properties = new ExtractorProperties(root, "ffmpeg", "ffprobe", 300, 3, 1000, false);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        LocalSegmentObjectStorageAdapter objects = new LocalSegmentObjectStorageAdapter(mapper, properties);
        Path source = temporaryDirectory.resolve("input-" + locations.hashCode() + ".mp4");
        generateVideo(source);
        String prefix = "capture-sessions/2d995d67-dd6f-4792-af22-480c43b37f2f/segments/00000001";
        var video = objects.putFile(prefix + "/source.mp4", source);
        SegmentTelemetry telemetry = new SegmentTelemetry(
                1,
                UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f"),
                1,
                Instant.parse("2026-08-25T12:00:00Z"),
                1_000,
                "segment_anchor",
                locations,
                List.of());
        var telemetryObject = objects.putJson(prefix + "/telemetry.json", telemetry);
        SegmentExtractionRequest request = new SegmentExtractionRequest(
                2, 0, telemetry.sessionId(), 1, "mobile:session:1",
                video.objectKey(), video.sha256(),
                telemetryObject.objectKey(), telemetryObject.sha256(),
                prefix, telemetry.capturedAtUtc(), 1_000,
                Instant.parse("2026-08-25T12:00:01Z"));
        CommandRunner runner = new CommandRunner();
        SegmentExtractionService service = new SegmentExtractionService(
                properties,
                objects,
                new LocalProcessingWorkspaceAdapter(properties),
                new RecordingSegmentStore(),
                new MediaProbe(runner, mapper, properties),
                new FrameTimestampProbe(runner, mapper, properties),
                new TelemetryAssociator(),
                new SamplingPlanner(),
                new GroupPlanner(),
                new FfmpegExtractor(runner, properties),
                Clock.fixed(Instant.parse("2026-08-25T12:00:02Z"), ZoneOffset.UTC));

        return new Extraction(service.extract(request), objects, prefix);
    }

    private static void generateVideo(Path destination) {
        var result = new CommandRunner().run(List.of(
                "ffmpeg", "-nostdin", "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x180:rate=10",
                "-frames:v", "10", "-c:v", "mpeg4", destination.toString()),
                java.time.Duration.ofMinutes(1));
        assertThat(result.exitCode()).as(result.stderr()).isZero();
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class RecordingSegmentStore implements CaptureSegmentStore {
        private String manifestObjectKey;

        @Override
        public String state(SegmentExtractionRequest request) {
            return "queued";
        }

        @Override
        public void markValidating(SegmentExtractionRequest request, Instant now) {
        }

        @Override
        public void markReady(SegmentExtractionRequest request, String key, int frameCount, Instant now) {
            manifestObjectKey = key;
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
