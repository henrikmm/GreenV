package br.com.greenv.frameextractor.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SegmentTelemetry(
        int schemaVersion,
        UUID sessionId,
        int segmentIndex,
        Instant capturedAtUtc,
        long monotonicStartNanos,
        String frameClockSource,
        List<LocationSample> locations,
        List<MotionSample> motions) {

    public SegmentTelemetry {
        locations = locations == null ? List.of() : List.copyOf(locations);
        motions = motions == null ? List.of() : List.copyOf(motions);
    }
}
