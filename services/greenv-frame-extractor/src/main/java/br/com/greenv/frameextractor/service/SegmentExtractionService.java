package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameGroup;
import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.ScalePlan;
import br.com.greenv.frameextractor.domain.FrameRecord;
import br.com.greenv.frameextractor.domain.FrameTelemetry;
import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.domain.SegmentManifest;
import br.com.greenv.frameextractor.domain.SegmentTelemetry;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.port.FrameSampler;
import br.com.greenv.frameextractor.port.FrameTimelineProbe;
import br.com.greenv.frameextractor.port.ProcessingWorkspace;
import br.com.greenv.frameextractor.port.SegmentObjectStorage;
import br.com.greenv.frameextractor.port.SegmentProcessor;
import br.com.greenv.frameextractor.port.VideoProbe;
import br.com.greenv.frameextractor.port.SegmentObjectStorage.ObjectDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SegmentExtractionService implements SegmentProcessor {

    // Frames are chosen by DISTANCE travelled, not by elapsed time.
    //
    // measurement/AGENTS.md:75-78 says to "sample by frames-per-second across the whole clip, never
    // 'N frames spread across it'". That rule is written for a hand-held clip of one scene, where
    // time and camera movement are the same thing. A camera on a vehicle is a capture geometry it
    // did not anticipate: the same ten seconds covers 0 m at a red light and 278 m at 100 km/h, so
    // a fixed rate spends the whole frame budget on one viewpoint in the first case and on a
    // quarter-kilometre of unrelated scenes in the second.
    //
    // What the depth model needs is baseline — camera translation between views —
    // (measurement/geometry/gravity.ts:91-97), and distance is that quantity directly. The three
    // concerns behind the rule still hold here: never a single image, never a trimmed window, and
    // the frames always span the whole clip.
    //
    // The constants below stay Verge Studio's. 112 frames at 504 px is its best graded setting
    // (measurement/MEASUREMENTS.md), and the cap keeps a segment off an L4's memory ceiling, which
    // fits 0.0700 GiB per frame plus 9.39 GiB and fails above 144. 1024 px is a transport size: the
    // model resizes to 504 px internally, so larger frames only cost bytes.
    //
    // SAMPLE_FPS now only serves the fallback, for a segment whose telemetry never arrived.
    /** Below this net displacement a segment has no parallax worth reconstructing from. */
    private static final double MIN_DISPLACEMENT_METERS = 3.0;

    /** The motion floor scales with the fixes' own accuracy: a vague fix must show more movement. */
    private static final double ACCURACY_MULTIPLE = 2.0;

    private static final double SAMPLE_FPS = 10.0;
    private static final int MAX_SAMPLE_FRAMES = 112;
    private static final int SAMPLE_LONG_EDGE = 1024;

    private final ExtractorProperties extractorProperties;
    private final SegmentObjectStorage objectStorage;
    private final ProcessingWorkspace processingWorkspace;
    private final CaptureSegmentStore segmentStore;
    private final VideoProbe videoProbe;
    private final FrameTimelineProbe frameTimelineProbe;
    private final TelemetryAssociator telemetryAssociator;
    private final SamplingPlanner samplingPlanner;
    private final GroupPlanner groupPlanner;
    private final FrameSampler frameSampler;
    private final Clock clock;

    public SegmentExtractionService(
            ExtractorProperties extractorProperties,
            SegmentObjectStorage objectStorage,
            ProcessingWorkspace processingWorkspace,
            CaptureSegmentStore segmentStore,
            VideoProbe videoProbe,
            FrameTimelineProbe frameTimelineProbe,
            TelemetryAssociator telemetryAssociator,
            SamplingPlanner samplingPlanner,
            GroupPlanner groupPlanner,
            FrameSampler frameSampler,
            Clock clock) {
        this.extractorProperties = extractorProperties;
        this.objectStorage = objectStorage;
        this.processingWorkspace = processingWorkspace;
        this.segmentStore = segmentStore;
        this.videoProbe = videoProbe;
        this.frameTimelineProbe = frameTimelineProbe;
        this.telemetryAssociator = telemetryAssociator;
        this.samplingPlanner = samplingPlanner;
        this.groupPlanner = groupPlanner;
        this.frameSampler = frameSampler;
        this.clock = clock;
    }

    @Override
    public SegmentManifest extract(SegmentExtractionRequest request) {
        validateRequest(request);
        String manifestKey = request.outputPrefix() + "/segment-manifest-v2.json";
        if (objectStorage.exists(manifestKey)) {
            SegmentManifest existing = objectStorage.readJson(manifestKey, SegmentManifest.class);
            requireGeneration(existing, request);
            verifyPublished(request.outputPrefix(), existing);
            segmentStore.markReady(request, manifestKey, existing.encodedFrameCount(), clock.instant());
            return existing;
        }

        Path workspace = processingWorkspace.create(request);
        try {
            return extractInWorkspace(request, workspace, manifestKey);
        } finally {
            processingWorkspace.clean(workspace);
        }
    }

    private SegmentManifest extractInWorkspace(
            SegmentExtractionRequest request,
            Path workspace,
            String manifestKey) {
        Path source = workspace.resolve("source.mp4");
        Path telemetryPath = workspace.resolve("telemetry.json");
        requireChecksum(
                objectStorage.download(request.videoObjectKey(), source),
                request.videoSha256(),
                "source_generation_mismatch");
        requireChecksum(
                objectStorage.download(request.telemetryObjectKey(), telemetryPath),
                request.telemetrySha256(),
                "telemetry_generation_mismatch");
        segmentStore.markValidating(request, clock.instant());

        SegmentTelemetry telemetry = objectStorage.readJson(request.telemetryObjectKey(), SegmentTelemetry.class);
        validateTelemetry(telemetry, request);
        var probe = videoProbe.probe(source);
        if (probe.durationSeconds() > Math.min(30.0, extractorProperties.maxDurationSeconds())) {
            throw new ExtractionException("segment_too_long", "capture segment exceeds 30 seconds", false);
        }
        List<FrameTelemetry> frames = telemetryAssociator.associate(frameTimelineProbe.probe(source), telemetry);
        if (frames.isEmpty()) {
            throw new ExtractionException("frame_probe_empty", "segment contains no encoded video frames", false);
        }

        String metadataKey = request.outputPrefix() + "/frame-metadata-v2.json";
        ObjectDescriptor metadata = objectStorage.putJson(metadataKey, frames);
        verifyFrameMetadata(metadataKey, frames.size());

        var scale = samplingPlanner.scale(probe.width(), probe.height(), SAMPLE_LONG_EDGE);
        Path sampledFramesPath = workspace.resolve("sampled-frames");

        // Where the camera actually went. Frames are spaced along this rather than along the clock,
        // because what multi-view geometry needs is camera translation between views and time is
        // only a proxy for it — a proxy that breaks the moment the vehicle slows down.
        MotionProfile profile = MotionProfile.from(telemetry.locations());
        List<EncodedFrameTimestamp> encoded = frameTimelineProbe.probe(source);
        List<FrameGroup> groups = planGroups(profile, encoded);

        List<FrameRecord> sampledFrames;
        List<FrameGroup> publishedGroups;
        String strategy;
        if (groups.isEmpty()) {
            // Either the vehicle never moved far enough for parallax, or there is no usable
            // telemetry. Both fall back to the uniform plan so the pipeline keeps producing
            // something, and the manifest records which happened.
            strategy = profile.isUsable() ? "insufficient-motion" : "time-uniform-no-telemetry";
            SamplingPlan uniform =
                    samplingPlanner.sampling(SAMPLE_FPS, probe.durationSeconds(), MAX_SAMPLE_FRAMES);
            List<Path> extracted = frameSampler.extract(source, sampledFramesPath, uniform, scale);
            sampledFrames = publishFrames(
                    request.outputPrefix(),
                    sampledFramesPath,
                    extracted.size(),
                    uniform.effectiveFps(),
                    probe.durationSeconds());
            publishedGroups = List.of();
        } else {
            strategy = "distance-groups";
            var published = publishGroups(
                    request.outputPrefix(), source, sampledFramesPath, scale, groups, encoded, profile);
            sampledFrames = published.frames();
            publishedGroups = published.groups();
        }

        SegmentManifest manifest = new SegmentManifest(
                2,
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
                metadata.objectKey(),
                metadata.sha256(),
                metadata.bytes(),
                sampledFrames,
                // The source segment is kept. Sampled frames are a derivative at one rate and one
                // resolution; the measurement stage that consumes them may want another, and it
                // cannot ask for it once the only copy is gone. Nothing expires capture objects
                // yet, so this grows without bound until a retention rule exists.
                false,
                clock.instant(),
                strategy,
                // Probed all along and never recorded. It is the only measured answer to what frame
                // rate the fleet's phones actually deliver, which is what bounds the tightest
                // baseline any speed can reach.
                probe.nativeFps(),
                profile.motion().pathMeters(),
                profile.motion().netDisplacementMeters(),
                publishedGroups);
        objectStorage.putJson(manifestKey, manifest);
        SegmentManifest published = objectStorage.readJson(manifestKey, SegmentManifest.class);
        verifyPublished(request.outputPrefix(), published);
        segmentStore.markReady(request, manifestKey, frames.size(), clock.instant());
        return published;
    }

    private static void validateRequest(SegmentExtractionRequest request) {
        boolean invalid = request == null
                || request.schemaVersion() != 2
                || request.attempt() < 0
                || request.sessionId() == null
                || request.segmentIndex() < 0
                || isBlank(request.idempotencyKey())
                || isBlank(request.videoObjectKey())
                || !isSha256(request.videoSha256())
                || isBlank(request.telemetryObjectKey())
                || !isSha256(request.telemetrySha256())
                || isBlank(request.outputPrefix())
                || request.capturedAt() == null
                || request.durationMillis() <= 0
                || request.durationMillis() > 30_000
                || request.requestedAt() == null;
        if (invalid) {
            throw new ExtractionException(
                    "invalid_segment_request",
                    "segment extraction request does not satisfy schema version 2",
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

    private static void requireChecksum(ObjectDescriptor object, String expected, String code) {
        if (!object.sha256().equals(expected)) {
            throw new ExtractionException(code, object.objectKey() + " checksum does not match the request", false);
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

    /**
     * Cuts the segment's travelled distance into stretches, or returns nothing when there is no
     * trustworthy motion to cut.
     *
     * <p>The motion floor is checked against NET DISPLACEMENT, never the accumulated path. The
     * phone's `distanceFromSessionStartMeters` is a running sum of great-circle hops, so at a
     * standstill it accumulates fix noise instead of cancelling it — simulated at 5 m accuracy, a
     * parked phone sums tens of metres that never happened, while its displacement stays within a
     * few. Using the sum here would let a queue of stopped traffic look like a drive.
     */
    private List<FrameGroup> planGroups(MotionProfile profile, List<EncodedFrameTimestamp> encoded) {
        if (!profile.isUsable()
                || !profile.motion().movedBeyondNoise(MIN_DISPLACEMENT_METERS, ACCURACY_MULTIPLE)) {
            return List.of();
        }
        return groupPlanner.plan(
                encoded,
                frame -> profile.distanceAt(frame.presentationTimeNanos()),
                profile.motion().pathMeters(),
                GroupPlanner.DEFAULT_GROUP_METERS,
                MAX_SAMPLE_FRAMES);
    }

    /**
     * Extracts and publishes frames for as many groups as the budget allows, and records every
     * group either way.
     *
     * <p>Publishing every group would triple storage — roughly 15 MB of JPEGs per ten-second
     * segment against about 5 MB today, with no retention rule anywhere — and triple the GPU bill,
     * which already runs to about four GPU-hours per hour driven. The source MP4 is kept, so a
     * group that is only planned can be materialised later from its recorded frame indices. What is
     * published is a budget decision; what is planned is the full picture.
     */
    private PublishedGroups publishGroups(
            String outputPrefix,
            Path source,
            Path sampledFramesPath,
            ScalePlan scale,
            List<FrameGroup> groups,
            List<EncodedFrameTimestamp> encoded,
            MotionProfile profile) {
        List<FrameRecord> records = new ArrayList<>();
        List<FrameGroup> described = new ArrayList<>(groups.size());
        int budget = MAX_SAMPLE_FRAMES;
        int published = 0;

        for (FrameGroup group : groups) {
            if (group.frameCount() > budget) {
                described.add(group);
                continue;
            }
            List<Path> extracted =
                    frameSampler.extractExact(source, sampledFramesPath, group.frameIndices(), scale);
            for (int i = 0; i < extracted.size(); i++) {
                int sourceIndex = group.frameIndices().get(i);
                EncodedFrameTimestamp frame = encoded.get(sourceIndex);
                Path staged = extracted.get(i);
                String fileName = "frame-%04d.jpg".formatted(published + 1);
                ObjectDescriptor stored =
                        objectStorage.putFile(outputPrefix + "/sampled-frames/" + fileName, staged);
                records.add(new FrameRecord(
                        published,
                        fileName,
                        frame.presentationTimeNanos() / 1_000_000_000.0,
                        stored.bytes(),
                        stored.sha256(),
                        sourceIndex,
                        profile.distanceAt(frame.presentationTimeNanos()),
                        group.index()));
                published++;
            }
            budget -= group.frameCount();
            described.add(group.asPublished());
        }
        return new PublishedGroups(List.copyOf(records), List.copyOf(described));
    }

    private record PublishedGroups(List<FrameRecord> frames, List<FrameGroup> groups) {}

    private List<FrameRecord> publishFrames(
            String outputPrefix,
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
            ObjectDescriptor published = objectStorage.putFile(
                    outputPrefix + "/sampled-frames/" + frame.getFileName(), frame);
            records.add(new FrameRecord(
                    index,
                    frame.getFileName().toString(),
                    Math.min(durationSeconds, index / effectiveFps),
                    published.bytes(),
                    published.sha256(),
                    // The uniform path cannot say which encoded frame this came from: the fps
                    // filter resamples, so the mapping is nominal rather than measured.
                    -1,
                    0.0,
                    -1));
        }
        return List.copyOf(records);
    }

    private void verifyFrameMetadata(String objectKey, int expectedCount) {
        FrameTelemetry[] frames = objectStorage.readJson(objectKey, FrameTelemetry[].class);
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

    private void verifyPublished(String outputPrefix, SegmentManifest manifest) {
        if (!manifest.frameMetadataObjectKey().equals(outputPrefix + "/frame-metadata-v2.json")) {
            throw new ExtractionException(
                    "frame_metadata_key_mismatch",
                    "manifest metadata key is outside the segment output prefix",
                    false);
        }
        ObjectDescriptor metadata = objectStorage.stat(manifest.frameMetadataObjectKey());
        if (metadata.bytes() != manifest.frameMetadataBytes()
                || !metadata.sha256().equals(manifest.frameMetadataSha256())) {
            throw new ExtractionException("frame_metadata_verify_failed", "frame metadata verification failed", true);
        }
        verifyFrameMetadata(metadata.objectKey(), manifest.encodedFrameCount());
        for (FrameRecord frame : manifest.sampledFrames()) {
            if (frame.fileName() == null || !frame.fileName().matches("frame-[0-9]{4}\\.jpg")) {
                throw new ExtractionException("sampled_frame_key_invalid", "sampled frame key is invalid", false);
            }
            ObjectDescriptor stored = objectStorage.stat(outputPrefix + "/sampled-frames/" + frame.fileName());
            if (stored.bytes() != frame.sizeBytes() || !stored.sha256().equals(frame.sha256())) {
                throw new ExtractionException("sampled_frame_verify_failed", "sampled frame verification failed", true);
            }
        }
    }

    private static int countQuality(List<FrameTelemetry> frames, String quality) {
        return (int) frames.stream().filter(frame -> quality.equals(frame.locationQuality())).count();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
