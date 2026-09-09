package br.com.greenv.frameextractor.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SegmentManifest(
        int schemaVersion,
        UUID sessionId,
        int segmentIndex,
        String sourceGeneration,
        String telemetryGeneration,
        long durationMillis,
        int encodedFrameCount,
        int goodLocationFrames,
        int degradedLocationFrames,
        int unavailableLocationFrames,
        int locationSampleCount,
        int motionSampleCount,
        String frameMetadataObjectKey,
        String frameMetadataSha256,
        long frameMetadataBytes,
        List<FrameRecord> sampledFrames,
        boolean sourceDeleted,
        Instant createdAt,
        String samplingStrategy,
        double nativeFps,
        double pathMeters,
        double netDisplacementMeters,
        double usableFixSpanSeconds,
        List<FrameGroup> groups) {

    public SegmentManifest {
        sampledFrames = sampledFrames == null ? List.of() : List.copyOf(sampledFrames);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
