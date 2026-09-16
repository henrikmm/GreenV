package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One measured stretch: an uploaded segment, or one window of it.
 *
 * <p>A segment used to be the unit of measurement and so this record used to be one row of {@code
 * capture_segments}. Since the extractor started cutting a segment into windows of about 25 m and
 * measuring each on its own, the stretch a dashboard lists and a crew is sent to is a window. Both
 * are described here rather than by two records, because outside the identity every field means
 * exactly the same thing at both scales and a second record would be the same fields with a
 * different spelling, plus a mapper between them.
 *
 * <p>{@link #windowIndex()} is what distinguishes them: null is the segment itself, measured
 * whole. The upload fields — the state, the checksums, the manifest, the frame count — always
 * describe the uploaded segment, because that is the only thing that was ever uploaded.
 */
public record CaptureSegmentDocument(
        UUID sessionId,
        int segmentIndex,
        String state,
        String idempotencyKey,
        Instant capturedAt,
        long durationMillis,
        String videoObjectKey,
        String videoSha256,
        Long videoBytes,
        String telemetryObjectKey,
        String telemetrySha256,
        Long telemetryBytes,
        String manifestObjectKey,
        Integer frameCount,
        String errorCode,
        String errorMessage,
        String measurementState,
        String measurementObjectKey,
        String measurementRunId,
        Boolean measurementIsMock,
        Instant measuredAt,
        /**
         * What a map can draw, derived from the packet rather than reported by the worker. Never
         * null: an unmeasured segment carries {@link MeasurementProjection#EMPTY} so a caller
         * reads a field rather than checking for a null object first.
         */
        MeasurementProjection measurement,
        /**
         * Where the stretch is, in words, cached from its own track centre. Null until the
         * resolver has been round; {@link SegmentPlace#isEmpty()} once it has been and found
         * nothing.
         */
        SegmentPlace place,
        /** Which window of the segment this is, or null when it is the segment measured whole. */
        Integer windowIndex,
        /**
         * Where the window runs, in metres along the segment's camera path. Null on a segment, and
         * null on a window the worker announced without them — which is not the same as zero.
         */
        Double windowStartMeters,
        Double windowEndMeters,
        /** How many windows the segment was cut into. Zero for a segment measured whole. */
        int windowCount,
        /** How many of those carry a measurement. */
        int measuredWindowCount,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasBothUploads() {
        return videoObjectKey != null && telemetryObjectKey != null;
    }

    public boolean isMeasured() {
        return "measured".equals(measurementState);
    }

    /** True when this row stands for a window rather than for the uploaded segment. */
    public boolean isWindow() {
        return windowIndex != null;
    }
}
