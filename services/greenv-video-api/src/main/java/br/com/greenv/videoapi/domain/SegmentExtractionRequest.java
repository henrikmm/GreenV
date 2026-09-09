package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

public record SegmentExtractionRequest(
        int schemaVersion,
        int attempt,
        UUID sessionId,
        int segmentIndex,
        String idempotencyKey,
        String videoObjectKey,
        String videoSha256,
        String telemetryObjectKey,
        String telemetrySha256,
        String outputPrefix,
        Instant capturedAt,
        long durationMillis,
        Instant requestedAt,
        // Copied from the capture session, because the worker that reads this message has no
        // database. Text rather than the enum: a message is data from another process, and the
        // extractor rejects an unknown sentido in its own failure vocabulary rather than inside a
        // JSON parser. Null when the capture never carried them.
        String rodovia,
        String sentido) {
}
