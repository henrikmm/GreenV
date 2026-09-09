package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.FrameTelemetry;
import br.com.greenv.frameextractor.domain.LocationSample;
import br.com.greenv.frameextractor.domain.MotionSample;
import br.com.greenv.frameextractor.domain.SegmentTelemetry;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TelemetryAssociator {

    private static final long MAX_LOCATION_AGE_NANOS = 2_000_000_000L;
    private static final long MAX_MOTION_AGE_NANOS = 100_000_000L;

    public List<FrameTelemetry> associate(
            List<EncodedFrameTimestamp> frames,
            SegmentTelemetry telemetry) {
        List<LocationSample> locations = telemetry.locations().stream()
                .sorted(Comparator.comparingLong(LocationSample::monotonicNanos))
                .toList();
        List<MotionSample> motions = telemetry.motions().stream()
                .sorted(Comparator.comparingLong(MotionSample::monotonicNanos))
                .toList();
        return frames.stream().map(frame -> {
            long captureNanos = telemetry.monotonicStartNanos() + frame.presentationTimeNanos();
            Nearest<LocationSample> location = nearest(locations, captureNanos, LocationSample::monotonicNanos);
            Nearest<MotionSample> motion = nearest(motions, captureNanos, MotionSample::monotonicNanos);
            LocationSample selectedLocation = location.ageNanos <= MAX_LOCATION_AGE_NANOS ? location.value : null;
            MotionSample selectedMotion = motion.ageNanos <= MAX_MOTION_AGE_NANOS ? motion.value : null;
            String quality = locationQuality(selectedLocation, location.ageNanos);
            return new FrameTelemetry(
                    frame.index(),
                    frame.presentationTimeNanos(),
                    captureNanos,
                    telemetry.capturedAtUtc().plus(frame.presentationTimeNanos(), ChronoUnit.NANOS),
                    frame.keyFrame(),
                    quality,
                    // The age is reported whenever a sample exists, including one dropped for being
                    // past the ceiling. Nulling it with the sample made "no fix was ever recorded"
                    // and "the nearest fix was 2.1 s away" identical in the metadata, and only the
                    // second of those says the phone had a receiver that was simply too slow.
                    ageMillis(location),
                    selectedLocation,
                    ageMillis(motion),
                    selectedMotion);
        }).toList();
    }

    private static Long ageMillis(Nearest<?> nearest) {
        return nearest.value == null ? null : nearest.ageNanos / 1_000_000;
    }

    private static String locationQuality(LocationSample location, long ageNanos) {
        if (location == null || ageNanos > MAX_LOCATION_AGE_NANOS) {
            return "unavailable";
        }
        if (location.horizontalAccuracyMeters() <= 10.0) {
            return "good";
        }
        return location.horizontalAccuracyMeters() <= 25.0 ? "degraded" : "unavailable";
    }

    private static <T> Nearest<T> nearest(List<T> values, long target, ToLong<T> timestamp) {
        T best = null;
        long bestAge = Long.MAX_VALUE;
        for (T value : values) {
            long age = Math.abs(timestamp.value(value) - target);
            if (age < bestAge) {
                best = value;
                bestAge = age;
            }
        }
        return new Nearest<>(best, bestAge);
    }

    private interface ToLong<T> {
        long value(T item);
    }

    private record Nearest<T>(T value, long ageNanos) {
    }
}
