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
        // Written onto the artifact and not only passed through the announcement, so the frames
        // and the measurement computed from them agree about which road they describe. A manifest
        // cut before the app asked keeps its nulls; nothing rewrites it with a later guess.
        String rodovia,
        String sentido) {
}
