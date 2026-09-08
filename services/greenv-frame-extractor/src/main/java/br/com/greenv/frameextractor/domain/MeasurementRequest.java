package br.com.greenv.frameextractor.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The announcement that a segment's frames are ready to be measured.
 *
 * It carries identifiers and nothing else. The measurement worker reads the manifest, the frame
 * metadata and the frames themselves from object storage under {@code outputPrefix}, so this
 * message never has to be kept in step with what those files contain.
 */
public record MeasurementRequest(
        int schemaVersion,
        UUID sessionId,
        int segmentIndex,
        String idempotencyKey,
        String outputPrefix,
        String sourceGeneration,
        int sampledFrameCount,
        Instant capturedAt,
        Instant requestedAt) {

    public static final int SCHEMA_VERSION = 1;

    public static MeasurementRequest from(
            SegmentExtractionRequest request, SegmentManifest manifest, Instant now) {
        return new MeasurementRequest(
                SCHEMA_VERSION,
                request.sessionId(),
                request.segmentIndex(),
                request.idempotencyKey(),
                request.outputPrefix(),
                manifest.sourceGeneration(),
                manifest.sampledFrames().size(),
                request.capturedAt(),
                now);
    }
}
