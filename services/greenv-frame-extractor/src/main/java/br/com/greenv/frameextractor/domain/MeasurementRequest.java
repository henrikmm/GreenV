package br.com.greenv.frameextractor.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The announcement that a segment's frames are ready to be measured.
 *
 * It carries identifiers and the road they belong to, and nothing else. The measurement worker
 * reads the manifest, the frame metadata and the frames themselves from object storage under
 * {@code outputPrefix}, so this message never has to be kept in step with what those files contain.
 *
 * <p>The road is the exception, and it has to be: the measurement worker calls Verge Studio, which
 * keeps exactly four fields per frame and cannot look anything up. Without {@code rodovia} and
 * {@code sentido} on this message the packet it produces cannot be joined to a {@code trecho} on
 * the map. They are taken from the manifest rather than from the request so the packet and the
 * frames it was computed from always name the same road.
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
        Instant requestedAt,
        String rodovia,
        String sentido) {

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
                now,
                manifest.rodovia(),
                manifest.sentido());
    }
}
