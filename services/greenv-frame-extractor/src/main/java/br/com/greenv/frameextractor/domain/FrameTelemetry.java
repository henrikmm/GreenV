package br.com.greenv.frameextractor.domain;

import java.time.Instant;

public record FrameTelemetry(
        int index,
        long presentationTimeNanos,
        long captureMonotonicNanos,
        Instant capturedAtUtc,
        boolean keyFrame,
        String locationQuality,
        Long locationAgeMillis,
        LocationSample location,
        Long motionAgeMillis,
        MotionSample motion) {
}
