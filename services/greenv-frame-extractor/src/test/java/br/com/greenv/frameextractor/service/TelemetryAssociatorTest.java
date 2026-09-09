package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.LocationSample;
import br.com.greenv.frameextractor.domain.MotionSample;
import br.com.greenv.frameextractor.domain.SegmentTelemetry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TelemetryAssociatorTest {

    private final TelemetryAssociator associator = new TelemetryAssociator();

    @Test
    void alignsEveryEncodedFrameToNearestMonotonicSensorSamples() {
        long origin = 10_000_000_000L;
        SegmentTelemetry telemetry = new SegmentTelemetry(
                1,
                UUID.randomUUID(),
                0,
                Instant.parse("2026-08-23T12:00:00Z"),
                origin,
                "segment_anchor",
                List.of(
                        location(origin, 5.0),
                        location(origin + 1_000_000_000L, 18.0),
                        location(origin + 4_000_000_000L, 30.0)),
                List.of(motion(origin + 20_000_000L)));

        var frames = associator.associate(
                List.of(
                        new EncodedFrameTimestamp(0, 0, true),
                        new EncodedFrameTimestamp(1, 1_020_000_000L, false),
                        new EncodedFrameTimestamp(2, 4_000_000_000L, false),
                        new EncodedFrameTimestamp(3, 7_000_000_000L, false)),
                telemetry);

        assertThat(frames).hasSize(4);
        assertThat(frames).extracting(frame -> frame.index()).containsExactly(0, 1, 2, 3);
        assertThat(frames).extracting(frame -> frame.locationQuality())
                .containsExactly("good", "degraded", "unavailable", "unavailable");
        assertThat(frames.get(0).motion()).isNotNull();
        assertThat(frames.get(1).motion()).isNull();
        assertThat(frames.get(1).capturedAtUtc()).isEqualTo("2026-08-23T12:00:01.020Z");
        assertThat(frames.get(3).location()).isNull();

        // Dropped, not absent. Frame 3's nearest fix is 3 s away and frame 1's nearest motion
        // sample 1 s away; both are past their ceiling, and both ages are still recorded, because
        // "no sensor ever reported" and "the reading was too old to trust" are different faults.
        assertThat(frames.get(3).locationAgeMillis()).isEqualTo(3_000);
        assertThat(frames.get(1).motionAgeMillis()).isEqualTo(1_000);
        assertThat(frames.get(0).locationAgeMillis()).isZero();
        assertThat(frames.get(0).motionAgeMillis()).isEqualTo(20);
    }

    @Test
    void emitsUnavailableMetadataWhenSensorsHaveNoSamples() {
        SegmentTelemetry telemetry = new SegmentTelemetry(
                1,
                UUID.randomUUID(),
                0,
                Instant.EPOCH,
                0,
                "segment_anchor",
                null,
                null);

        var frames = associator.associate(
                List.of(new EncodedFrameTimestamp(0, 0, true)), telemetry);

        assertThat(frames).singleElement().satisfies(frame -> {
            assertThat(frame.locationQuality()).isEqualTo("unavailable");
            assertThat(frame.location()).isNull();
            assertThat(frame.motion()).isNull();
            assertThat(frame.locationAgeMillis()).isNull();
            assertThat(frame.motionAgeMillis()).isNull();
        });
    }

    private static LocationSample location(long nanos, double accuracy) {
        return new LocationSample(
                nanos,
                -23.5505,
                -46.6333,
                760.0,
                accuracy,
                3.0,
                12.5,
                0.5,
                180.0,
                2.0,
                42.0);
    }

    private static MotionSample motion(long nanos) {
        return new MotionSample(
                nanos,
                0, 0, 0, 1,
                0, 0, -9.81,
                0.1, 0.2, 0.3,
                0.01, 0.02, 0.03);
    }
}
