package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.com.greenv.frameextractor.domain.LocationSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MotionProfileTest {

    private static final double BASE_LAT = -23.5;
    private static final double BASE_LON = -46.6;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    @Test
    void integratesSpeedIntoDistanceAlongTheSegment() {
        // 10 s at a steady 16.67 m/s is 60 km/h, which covers 166.7 m.
        var profile = MotionProfile.from(steadyDrive(16.666_67, 11));

        assertThat(profile.motion().pathMeters()).isCloseTo(166.7, within(0.5));
        assertThat(profile.motion().fromSpeed()).isTrue();
        assertThat(profile.distanceAt(seconds(5))).isCloseTo(83.3, within(0.5));
    }

    @Test
    void clampsFramesThatFallOutsideTheFixRange() {
        var profile = MotionProfile.from(steadyDrive(10.0, 5));

        // The camera starts before the first fix arrives and stops after the last.
        assertThat(profile.distanceAt(seconds(-3))).isEqualTo(0.0);
        assertThat(profile.distanceAt(seconds(99))).isEqualTo(profile.motion().pathMeters());
    }

    @Test
    void fallsBackToPositionsWhenThePlatformReportsNoSpeed() {
        List<LocationSample> fixes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // 10 m north per second, and speed reported as 0.0 the way Android does when the
            // provider has none.
            fixes.add(fix(seconds(i), BASE_LAT + metresNorth(i * 10.0), BASE_LON, 5.0, 0.0));
        }
        var profile = MotionProfile.from(fixes);

        assertThat(profile.motion().fromSpeed()).isFalse();
        assertThat(profile.motion().pathMeters()).isCloseTo(50.0, within(1.0));
    }

    /**
     * The reason the motion gate uses displacement and not the accumulated path: a parked phone's
     * fixes wander, and the wander sums instead of cancelling.
     */
    @Test
    void aParkedVehicleInventsPathButNotDisplacement() {
        Random random = new Random(7);
        List<LocationSample> fixes = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            fixes.add(fix(
                    seconds(i),
                    BASE_LAT + metresNorth(random.nextGaussian() * 5.0),
                    BASE_LON + metresEast(random.nextGaussian() * 5.0),
                    5.0,
                    0.0));
        }
        var motion = MotionProfile.from(fixes).motion();

        assertThat(motion.pathMeters()).isGreaterThan(20.0);
        assertThat(motion.netDisplacementMeters()).isLessThan(motion.pathMeters() / 2);
        assertThat(motion.movedBeyondNoise(3.0, 2.0)).isFalse();
    }

    @Test
    void aMovingVehicleClearsTheNoiseFloor() {
        var motion = MotionProfile.from(steadyDrive(16.666_67, 11)).motion();

        assertThat(motion.movedBeyondNoise(3.0, 2.0)).isTrue();
    }

    /**
     * How much of the segment the fixes cover, which is the evidence behind an abstention. Two
     * fixes are two fixes and {@code isUsable()} says so, but 19 ms of them describe 19 ms.
     */
    @Test
    void reportsTheSecondsSpannedByTheUsableFixes() {
        var sparse = MotionProfile.from(List.of(
                fix(seconds(0), BASE_LAT, BASE_LON, 5.0, 0.0),
                fix(19_400_000L, BASE_LAT, BASE_LON, 5.0, 0.0)));

        assertThat(sparse.isUsable()).isTrue();
        assertThat(sparse.fixSpanSeconds()).isCloseTo(0.0194, within(0.0001));
        assertThat(MotionProfile.from(steadyDrive(16.666_67, 11)).fixSpanSeconds())
                .isCloseTo(10.0, within(0.001));
        assertThat(MotionProfile.from(List.of()).fixSpanSeconds()).isZero();
    }

    /**
     * The floor a browser's IP-derived position sets. At 50 km accuracy it stands at 100 km, so a
     * car at 60 km/h is refused for exactly the same arithmetic as a parked phone - which is why
     * the noise floor has to be readable on its own and not only applied.
     */
    @Test
    void scalesTheNoiseFloorWithTheFixesOwnAccuracy() {
        assertThat(MotionProfile.from(steadyDrive(16.666_67, 11)).motion().noiseFloorMeters(3.0, 2.0))
                .isEqualTo(10.0);

        List<LocationSample> vague = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            vague.add(fix(
                    seconds(i), BASE_LAT + metresNorth(i * 16.666_67), BASE_LON, 50_000.0, 16.666_67));
        }
        var motion = MotionProfile.from(vague).motion();

        assertThat(motion.netDisplacementMeters()).isCloseTo(166.7, within(0.5));
        assertThat(motion.noiseFloorMeters(3.0, 2.0)).isEqualTo(100_000.0);
        assertThat(motion.movedBeyondNoise(3.0, 2.0)).isFalse();
    }

    @Test
    void reportsUnknownWithoutTwoUsableFixes() {
        assertThat(MotionProfile.from(List.of()).motion().isKnown()).isFalse();
        assertThat(MotionProfile.from(null).isUsable()).isFalse();
        assertThat(MotionProfile.from(steadyDrive(10.0, 1)).motion().isKnown()).isFalse();
    }

    private static List<LocationSample> steadyDrive(double metersPerSecond, int fixes) {
        List<LocationSample> samples = new ArrayList<>(fixes);
        for (int i = 0; i < fixes; i++) {
            samples.add(fix(
                    seconds(i),
                    BASE_LAT + metresNorth(metersPerSecond * i),
                    BASE_LON,
                    5.0,
                    metersPerSecond));
        }
        return samples;
    }

    private static LocationSample fix(
            long monotonicNanos, double latitude, double longitude, double accuracy, double speed) {
        return new LocationSample(
                monotonicNanos, latitude, longitude, null, accuracy, null, speed, null, null, null, null);
    }

    private static long seconds(long value) {
        return value * 1_000_000_000L;
    }

    private static double metresNorth(double meters) {
        return meters / METERS_PER_DEGREE_LAT;
    }

    private static double metresEast(double meters) {
        return meters / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(BASE_LAT)));
    }
}
