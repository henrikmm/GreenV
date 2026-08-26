package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.domain.FrameManifest;
import br.com.greenv.frameextractor.domain.FrameRecord;
import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.port.LegacyPipelineStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ExtractionService {

    private final ExtractorProperties properties;
    private final LegacyPipelineStore store;
    private final MediaProbe mediaProbe;
    private final SamplingPlanner samplingPlanner;
    private final FfmpegExtractor ffmpegExtractor;
    private final Clock clock;

    public ExtractionService(
            ExtractorProperties properties,
            LegacyPipelineStore store,
            MediaProbe mediaProbe,
            SamplingPlanner samplingPlanner,
            FfmpegExtractor ffmpegExtractor,
            Clock clock) {
        this.properties = properties;
        this.store = store;
        this.mediaProbe = mediaProbe;
        this.samplingPlanner = samplingPlanner;
        this.ffmpegExtractor = ffmpegExtractor;
        this.clock = clock;
    }

    public FrameManifest extract(FrameExtractionRequest request) {
        validate(request);
        Path source = store.resolve(request.sourceUri());
        Path outputRoot = store.resolve(request.outputPrefixUri());
        Path finalFrames = outputRoot.resolve("frames");
        Path finalManifest = outputRoot.resolve("manifest.json");

        if (Files.isRegularFile(finalManifest)) {
            FrameManifest existing = store.readManifest(finalManifest);
            if (!existing.sourceGeneration().equals(request.sourceGeneration())) {
                throw new ExtractionException(
                        "manifest_generation_conflict",
                        "published manifest belongs to a different source generation",
                        false);
            }
            if (Files.exists(source)) {
                store.delete(source);
            }
            store.markReady(request.statusUri(), finalManifest, true, clock.instant());
            return existing;
        }

        if (!Files.isRegularFile(source)) {
            throw new ExtractionException("source_missing", "source video is not available", true);
        }
        String actualGeneration = store.sha256(source);
        if (!actualGeneration.equals(request.sourceGeneration())) {
            throw new ExtractionException(
                    "source_generation_mismatch",
                    "source checksum does not match the queued generation",
                    false);
        }

        store.markState(request.statusUri(), "extracting", clock.instant());
        var probe = mediaProbe.probe(source);
        if (probe.durationSeconds() > properties.maxDurationSeconds()) {
            throw new ExtractionException(
                    "video_too_long",
                    "video duration exceeds the configured five-minute limit",
                    false);
        }

        SamplingPlan sampling = samplingPlanner.sampling(
                request.requestedFps(), probe.durationSeconds(), request.maxFrames());
        var scale = samplingPlanner.scale(probe.width(), probe.height(), request.longEdge());
        Path attemptRoot = outputRoot
                .resolve("attempts")
                .resolve(request.sourceGeneration().substring(0, Math.min(16, request.sourceGeneration().length()))
                        + "-" + request.attempt());
        Path attemptFrames = attemptRoot.resolve("frames");
        store.deleteTree(attemptRoot);

        List<Path> extracted = ffmpegExtractor.extract(source, attemptFrames, sampling, scale);
        SamplingPlan actualSampling = new SamplingPlan(
                extracted.size(), sampling.effectiveFps(), sampling.capped(), sampling.requestedCount());
        FrameManifest manifest = new FrameManifest(
                1,
                request.jobId(),
                request.sourceGeneration(),
                request.requestedFps(),
                actualSampling,
                scale,
                probe,
                clock.instant(),
                describeFrames(extracted, actualSampling.effectiveFps(), probe.durationSeconds()));

        Path attemptManifest = attemptRoot.resolve("manifest.json");
        store.writeManifest(attemptManifest, manifest);
        verifyManifest(attemptFrames, store.readManifest(attemptManifest));

        if (Files.exists(finalFrames)) {
            store.deleteTree(finalFrames);
        }
        store.moveDirectory(attemptFrames, finalFrames);
        verifyManifest(finalFrames, manifest);
        store.writeManifest(finalManifest, manifest);
        store.readManifest(finalManifest);

        store.delete(source);
        store.markReady(request.statusUri(), finalManifest, true, clock.instant());
        store.deleteTree(outputRoot.resolve("attempts"));
        return manifest;
    }

    private static void validate(FrameExtractionRequest request) {
        boolean invalid = request == null
                || request.schemaVersion() != 1
                || request.attempt() < 0
                || request.jobId() == null
                || isBlank(request.idempotencyKey())
                || isBlank(request.sourceUri())
                || isBlank(request.statusUri())
                || isBlank(request.outputPrefixUri())
                || request.sourceGeneration() == null
                || !request.sourceGeneration().matches("[0-9a-f]{64}")
                || !(request.requestedFps() >= 0.1 && request.requestedFps() <= 60)
                || request.maxFrames() < 2
                || request.maxFrames() > 512
                || request.longEdge() < 64
                || request.longEdge() > 4096
                || request.requestedAt() == null;
        if (invalid) {
            throw new ExtractionException(
                    "invalid_extraction_request",
                    "frame extraction request does not satisfy schema version 1",
                    false);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<FrameRecord> describeFrames(
            List<Path> frames,
            double effectiveFps,
            double durationSeconds) {
        List<FrameRecord> records = new ArrayList<>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            Path frame = frames.get(index);
            try {
                records.add(new FrameRecord(
                        index + 1,
                        frame.getFileName().toString(),
                        Math.min(durationSeconds, index / effectiveFps),
                        Files.size(frame),
                        store.sha256(frame)));
            } catch (IOException exception) {
                throw new ExtractionException("frame_stat_failed", "could not inspect extracted frame", true, exception);
            }
        }
        return List.copyOf(records);
    }

    private void verifyManifest(Path framesDirectory, FrameManifest manifest) {
        if (manifest.frames().size() != manifest.sampling().count()) {
            throw new ExtractionException("manifest_count_mismatch", "manifest frame count is inconsistent", true);
        }
        for (FrameRecord frame : manifest.frames()) {
            Path path = framesDirectory.resolve(frame.fileName()).normalize();
            if (!path.startsWith(framesDirectory.normalize()) || !Files.isRegularFile(path)) {
                throw new ExtractionException("manifest_frame_missing", "manifest references a missing frame", true);
            }
            try {
                if (Files.size(path) != frame.sizeBytes() || !store.sha256(path).equals(frame.sha256())) {
                    throw new ExtractionException("manifest_checksum_mismatch", "frame verification failed", true);
                }
            } catch (IOException exception) {
                throw new ExtractionException("manifest_verify_failed", "could not verify frame", true, exception);
            }
        }
    }
}
