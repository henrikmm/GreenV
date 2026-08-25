package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameRecord;
import br.com.greenv.frameextractor.domain.FrameTelemetry;
import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.domain.SegmentManifest;
import br.com.greenv.frameextractor.domain.SegmentTelemetry;
import br.com.greenv.frameextractor.storage.CaptureSegmentRepository;
import br.com.greenv.frameextractor.storage.LocalPipelineStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SegmentExtractionService {

    private static final double SAMPLE_FPS = 2.0;
    private static final int MAX_SAMPLE_FRAMES = 64;
    private static final int SAMPLE_LONG_EDGE = 1280;

    private final ExtractorProperties properties;
    private final LocalPipelineStore store;
    private final CaptureSegmentRepository repository;
    private final MediaProbe mediaProbe;
    private final FrameTimestampProbe timestampProbe;
    private final TelemetryAssociator telemetryAssociator;
    private final SamplingPlanner samplingPlanner;
    private final FfmpegExtractor ffmpegExtractor;
    private final Clock clock;

    public SegmentExtractionService(
            ExtractorProperties properties,
            LocalPipelineStore store,
            CaptureSegmentRepository repository,
            MediaProbe mediaProbe,
            FrameTimestampProbe timestampProbe,
            TelemetryAssociator telemetryAssociator,
            SamplingPlanner samplingPlanner,
            FfmpegExtractor ffmpegExtractor,
            Clock clock) {
        this.properties = properties;
        this.store = store;
        this.repository = repository;
        this.mediaProbe = mediaProbe;
        this.timestampProbe = timestampProbe;
        this.telemetryAssociator = telemetryAssociator;
        this.samplingPlanner = samplingPlanner;
        this.ffmpegExtractor = ffmpegExtractor;
        this.clock = clock;
    }

    public SegmentManifest extract(SegmentExtractionRequest request) {
        validateRequest(request);
        Path source = store.resolve(request.videoUri());
        Path telemetryPath = store.resolve(request.telemetryUri());
        Path outputRoot = store.resolve(request.outputPrefixUri());
        Path manifestPath = outputRoot.resolve("segment-manifest-v1.json");

        if (Files.isRegularFile(manifestPath)) {
            SegmentManifest existing = store.readJson(manifestPath, SegmentManifest.class);
            requireGeneration(existing, request);
            repository.markReady(request, manifestPath.toUri().toString(), existing.encodedFrameCount(), clock.instant());
            if (Files.isRegularFile(source)) {
                store.delete(source);
            }
            return existing;
        }

        requireFile(source, "source_missing", "segment video is not available");
        requireFile(telemetryPath, "telemetry_missing", "segment telemetry is not available");
        requireChecksum(source, request.videoSha256(), "source_generation_mismatch");
        requireChecksum(telemetryPath, request.telemetrySha256(), "telemetry_generation_mismatch");
        repository.markValidating(request, clock.instant());

        SegmentTelemetry telemetry = store.readJson(telemetryPath, SegmentTelemetry.class);
        validateTelemetry(telemetry, request);
        var probe = mediaProbe.probe(source);
        if (probe.durationSeconds() > Math.min(30.0, properties.maxDurationSeconds())) {
            throw new ExtractionException("segment_too_long", "capture segment exceeds 30 seconds", false);
        }
        List<FrameTelemetry> frames = telemetryAssociator.associate(timestampProbe.probe(source), telemetry);
        if (frames.isEmpty()) {
            throw new ExtractionException("frame_probe_empty", "segment contains no encoded video frames", false);
        }

        Path metadataPath = outputRoot.resolve("frame-metadata-v1.json");
        store.writeJson(metadataPath, frames);
        verifyFrameMetadata(metadataPath, frames.size());

        SamplingPlan requestedSampling = samplingPlanner.sampling(
                SAMPLE_FPS,
                probe.durationSeconds(),
                MAX_SAMPLE_FRAMES);
        var scale = samplingPlanner.scale(probe.width(), probe.height(), SAMPLE_LONG_EDGE);
        Path attemptRoot = outputRoot.resolve("attempts").resolve(request.videoSha256().substring(0, 16));
        Path attemptFrames = attemptRoot.resolve("sampled-frames");
        List<Path> extracted = ffmpegExtractor.extract(source, attemptFrames, requestedSampling, scale);
        Path finalFrames = outputRoot.resolve("sampled-frames");
        store.moveDirectory(attemptFrames, finalFrames);
        List<FrameRecord> sampledFrames = describeFrames(
                finalFrames,
                extracted.size(),
                requestedSampling.effectiveFps(),
                probe.durationSeconds());

        String metadataSha256 = store.sha256(metadataPath);
        long metadataBytes = fileSize(metadataPath);
        SegmentManifest unpublished = new SegmentManifest(
                1,
                request.sessionId(),
                request.segmentIndex(),
                request.videoSha256(),
                request.telemetrySha256(),
                request.durationMillis(),
                frames.size(),
                countQuality(frames, "good"),
                countQuality(frames, "degraded"),
                countQuality(frames, "unavailable"),
                telemetry.locations().size(),
                telemetry.motions().size(),
                metadataPath.toUri().toString(),
                metadataSha256,
                metadataBytes,
                sampledFrames,
                false,
                clock.instant());
        store.writeJson(manifestPath, unpublished);
        SegmentManifest verified = store.readJson(manifestPath, SegmentManifest.class);
        verifyPublished(outputRoot, verified);

        store.delete(source);
        SegmentManifest published = new SegmentManifest(
                verified.schemaVersion(),
                verified.sessionId(),
                verified.segmentIndex(),
                verified.sourceGeneration(),
                verified.telemetryGeneration(),
                verified.durationMillis(),
                verified.encodedFrameCount(),
                verified.goodLocationFrames(),
                verified.degradedLocationFrames(),
                verified.unavailableLocationFrames(),
                verified.locationSampleCount(),
                verified.motionSampleCount(),
                verified.frameMetadataUri(),
                verified.frameMetadataSha256(),
                verified.frameMetadataBytes(),
                verified.sampledFrames(),
                true,
                verified.createdAt());
        store.writeJson(manifestPath, published);
        verifyPublished(outputRoot, store.readJson(manifestPath, SegmentManifest.class));
        store.deleteTree(outputRoot.resolve("attempts"));
        repository.markReady(request, manifestPath.toUri().toString(), frames.size(), clock.instant());
        return published;
    }

    private static void validateRequest(SegmentExtractionRequest request) {
        boolean invalid = request == null
                || request.schemaVersion() != 1
                || request.attempt() < 0
                || request.sessionId() == null
                || request.segmentIndex() < 0
                || isBlank(request.idempotencyKey())
                || isBlank(request.videoUri())
                || !isSha256(request.videoSha256())
                || isBlank(request.telemetryUri())
                || !isSha256(request.telemetrySha256())
                || isBlank(request.outputPrefixUri())
                || request.capturedAt() == null
                || request.durationMillis() <= 0
                || request.durationMillis() > 30_000
                || request.requestedAt() == null;
        if (invalid) {
            throw new ExtractionException(
                    "invalid_segment_request",
                    "segment extraction request does not satisfy schema version 1",
                    false);
        }
    }

    private static void validateTelemetry(SegmentTelemetry telemetry, SegmentExtractionRequest request) {
        boolean invalid = telemetry == null
                || telemetry.schemaVersion() != 1
                || !request.sessionId().equals(telemetry.sessionId())
                || request.segmentIndex() != telemetry.segmentIndex()
                || telemetry.capturedAtUtc() == null
                || telemetry.monotonicStartNanos() < 0
                || isBlank(telemetry.frameClockSource())
                || telemetry.locations().stream().anyMatch(location ->
                        location.monotonicNanos() < 0
                                || !Double.isFinite(location.latitude())
                                || location.latitude() < -90
                                || location.latitude() > 90
                                || !Double.isFinite(location.longitude())
                                || location.longitude() < -180
                                || location.longitude() > 180
                                || !Double.isFinite(location.horizontalAccuracyMeters())
                                || location.horizontalAccuracyMeters() < 0)
                || telemetry.motions().stream().anyMatch(motion -> motion.monotonicNanos() < 0);
        if (invalid) {
            throw new ExtractionException(
                    "invalid_segment_telemetry",
                    "segment telemetry does not match the queued capture segment",
                    false);
        }
    }

    private void requireChecksum(Path path, String expected, String code) {
        if (!store.sha256(path).equals(expected)) {
            throw new ExtractionException(code, path.getFileName() + " checksum does not match the request", false);
        }
    }

    private static void requireFile(Path path, String code, String message) {
        if (!Files.isRegularFile(path)) {
            throw new ExtractionException(code, message, true);
        }
    }

    private static void requireGeneration(SegmentManifest manifest, SegmentExtractionRequest request) {
        if (!manifest.sourceGeneration().equals(request.videoSha256())
                || !manifest.telemetryGeneration().equals(request.telemetrySha256())) {
            throw new ExtractionException(
                    "segment_manifest_generation_conflict",
                    "published manifest belongs to different source objects",
                    false);
        }
    }

    private List<FrameRecord> describeFrames(
            Path directory,
            int count,
            double effectiveFps,
            double durationSeconds) {
        List<FrameRecord> records = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Path frame = directory.resolve("frame-%04d.jpg".formatted(index + 1));
            if (!Files.isRegularFile(frame)) {
                throw new ExtractionException("sampled_frame_missing", "sampled frame is missing", true);
            }
            records.add(new FrameRecord(
                    index,
                    frame.getFileName().toString(),
                    Math.min(durationSeconds, index / effectiveFps),
                    fileSize(frame),
                    store.sha256(frame)));
        }
        return List.copyOf(records);
    }

    private void verifyFrameMetadata(Path path, int expectedCount) {
        FrameTelemetry[] frames = store.readJson(path, FrameTelemetry[].class);
        if (frames.length != expectedCount) {
            throw new ExtractionException(
                    "frame_metadata_count_mismatch",
                    "frame metadata does not contain every encoded frame",
                    true);
        }
        for (int index = 0; index < frames.length; index++) {
            if (frames[index].index() != index) {
                throw new ExtractionException(
                        "frame_metadata_index_mismatch",
                        "frame metadata indices are not contiguous",
                        true);
            }
        }
    }

    private void verifyPublished(Path outputRoot, SegmentManifest manifest) {
        Path metadata = store.resolve(manifest.frameMetadataUri());
        if (!Files.isRegularFile(metadata)
                || fileSize(metadata) != manifest.frameMetadataBytes()
                || !store.sha256(metadata).equals(manifest.frameMetadataSha256())) {
            throw new ExtractionException("frame_metadata_verify_failed", "frame metadata verification failed", true);
        }
        verifyFrameMetadata(metadata, manifest.encodedFrameCount());
        Path framesRoot = outputRoot.resolve("sampled-frames");
        for (FrameRecord frame : manifest.sampledFrames()) {
            Path path = framesRoot.resolve(frame.fileName()).normalize();
            if (!path.startsWith(framesRoot.normalize())
                    || !Files.isRegularFile(path)
                    || fileSize(path) != frame.sizeBytes()
                    || !store.sha256(path).equals(frame.sha256())) {
                throw new ExtractionException("sampled_frame_verify_failed", "sampled frame verification failed", true);
            }
        }
    }

    private static int countQuality(List<FrameTelemetry> frames, String quality) {
        return (int) frames.stream().filter(frame -> quality.equals(frame.locationQuality())).count();
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new ExtractionException("artifact_stat_failed", "could not inspect published artifact", true, exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
