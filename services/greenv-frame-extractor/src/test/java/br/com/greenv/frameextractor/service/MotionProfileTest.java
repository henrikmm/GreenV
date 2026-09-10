package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.LocationSample;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MotionProfileTest {

    private static final double BASE_LAT = -23.5;
    private static final double BASE_LON = -46.6;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    /**
     * A real phone's monotonic clock, read from the capture of 9 September 2026 that exposed the
     * bug: forty-one minutes of uptime. A fixture anchored near zero cannot tell a segment clock
     * from an uptime clock, and for two days it did not.
     */
    private static final long UPTIME = 2_458_962_421_000L;

    @Test
    void integratesSpeedIntoDistanceAlongTheSegment() {
        // 10 s at a steady 16.67 m/s is 60 km/h, which covers 166.7 m.
        var profile = MotionProfile.from(steadyDrive(16.666_67, 11), UPTIME);

        assertThat(profile.motion().pathMeters()).isCloseTo(166.7, within(0.5));
        assertThat(profile.motion().fromSpeed()).isTrue();
        assertThat(profile.distanceAt(frameAt(seconds(5)))).isCloseTo(83.3, within(0.5));
    }

    /**
     * Fixes carry the phone's uptime; encoded frames carry presentation time from zero. Reading a
     * frame against the uptime clock put every frame before the first fix, so every frame took
     * distance zero, every group collapsed to a single viewpoint and no capture between 7 and
     * 9 September 2026 produced one.
     */
    @Test
    void placesFramesOnTheSegmentClockAndNotOnThePhonesUptimeClock() {
        var profile = MotionProfile.from(steadyDrive(10.0, 11), UPTIME);

        assertThat(profile.distanceAt(frameAt(seconds(0)))).isZero();
        assertThat(profile.distanceAt(frameAt(seconds(5)))).isCloseTo(50.0, within(0.5));
        assertThat(profile.distanceAt(frameAt(seconds(10)))).isCloseTo(100.0, within(0.5));
    }

    @Test
    void clampsFramesThatFallOutsideTheFixRange() {
        var profile = MotionProfile.from(steadyDrive(10.0, 5), UPTIME);

        // The camera starts before the first fix arrives and stops after the last.
        assertThat(profile.distanceAt(frameAt(seconds(-3)))).isEqualTo(0.0);
        assertThat(profile.distanceAt(frameAt(seconds(99)))).isEqualTo(profile.motion().pathMeters());
    }

    @Test
    void fallsBackToPositionsWhenThePlatformReportsNoSpeed() {
        List<LocationSample> fixes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // 10 m north per second, and speed reported as 0.0 the way Android does when the
            // provider has none.
            fixes.add(fix(UPTIME + seconds(i), BASE_LAT + metresNorth(i * 10.0), BASE_LON, 5.0, 0.0));
        }
        var profile = MotionProfile.from(fixes, UPTIME);

        assertThat(profile.motion().fromSpeed()).isFalse();
        assertThat(profile.motion().pathMeters()).isCloseTo(50.0, within(1.0));
    }

    /**
     * Why the gate falls back to displacement when there is no speed: a parked phone's fixes
     * wander, and the wander sums instead of cancelling.
     */
    @Test
    void aParkedVehicleInventsPathButNotDisplacement() {
        Random random = new Random(7);
        List<LocationSample> fixes = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            fixes.add(fix(
                    UPTIME + seconds(i),
                    BASE_LAT + metresNorth(random.nextGaussian() * 5.0),
                    BASE_LON + metresEast(random.nextGaussian() * 5.0),
                    5.0,
                    0.0));
        }
        var motion = MotionProfile.from(fixes, UPTIME).motion();

        assertThat(motion.fromSpeed()).isFalse();
        assertThat(motion.pathMeters()).isGreaterThan(20.0);
        assertThat(motion.movementMeters()).isEqualTo(motion.netDisplacementMeters());
        assertThat(motion.netDisplacementMeters()).isLessThan(motion.pathMeters() / 2);
        assertThat(motion.movedBeyondNoise(3.0, 2.0)).isFalse();
    }

    @Test
    void aMovingVehicleClearsTheNoiseFloor() {
        var motion = MotionProfile.from(steadyDrive(16.666_67, 11), UPTIME).motion();

        assertThat(motion.movedBeyondNoise(3.0, 2.0)).isTrue();
    }

    /**
     * Doppler speed never reads a position, so the fixes' horizontal accuracy says nothing about
     * how well it measured the path. Scaling the floor by that accuracy demanded 25 m in ten
     * seconds — 9 km/h — and refused 8.8 m of walking the phone had actually measured (segment
     * 01a08885 #2, 9 September 2026).
     */
    @Test
    void keepsTheFloorAtTheMinimumWhenThePathCameFromSpeed() {
        var walk = MotionProfile.from(steadyDrive(0.87, 11, 12.5), UPTIME).motion();

        assertThat(walk.fromSpeed()).isTrue();
        assertThat(walk.movementMeters()).isCloseTo(8.7, within(0.2));
        assertThat(walk.noiseFloorMeters(3.0, 2.0)).isEqualTo(3.0);
        assertThat(walk.movedBeyondNoise(3.0, 2.0)).isTrue();
    }

    /** A stroll of a couple of metres is still not two viewpoints, however well measured. */
    @Test
    void refusesAPathTooShortForParallaxEvenFromSpeed() {
        var shuffle = MotionProfile.from(steadyDrive(0.15, 11, 12.5), UPTIME).motion();

        assertThat(shuffle.fromSpeed()).isTrue();
        assertThat(shuffle.movementMeters()).isLessThan(3.0);
        assertThat(shuffle.movedBeyondNoise(3.0, 2.0)).isFalse();
    }

    /**
     * How much of the segment the fixes cover, which is the evidence behind an abstention. Two
     * fixes are two fixes and {@code isUsable()} says so, but 19 ms of them describe 19 ms.
     */
    @Test
    void reportsTheSecondsSpannedByTheUsableFixes() {
        var sparse = MotionProfile.from(
                List.of(
                        fix(UPTIME, BASE_LAT, BASE_LON, 5.0, 0.0),
                        fix(UPTIME + 19_400_000L, BASE_LAT, BASE_LON, 5.0, 0.0)),
                UPTIME);

        assertThat(sparse.isUsable()).isTrue();
        assertThat(sparse.fixSpanSeconds()).isCloseTo(0.0194, within(0.0001));
        assertThat(MotionProfile.from(steadyDrive(16.666_67, 11), UPTIME).fixSpanSeconds())
                .isCloseTo(10.0, within(0.001));
        assertThat(MotionProfile.from(List.of(), UPTIME).fixSpanSeconds()).isZero();
    }

    /**
     * The floor a browser's IP-derived position sets. It reports no speed at all, so the path is a
     * sum of positions and the accuracy is what sets the noise: at 50 km it stands at 100 km, which
     * no ten-second segment reaches at any speed, so "did not move" is the only output the
     * arithmetic permits and therefore not a finding.
     */
    @Test
    void scalesTheNoiseFloorWithTheFixesOwnAccuracyWhenThereIsNoSpeed() {
        List<LocationSample> vague = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            vague.add(fix(
                    UPTIME + seconds(i),
                    BASE_LAT + metresNorth(i * 16.666_67),
                    BASE_LON,
                    50_000.0,
                    null));
        }
        var motion = MotionProfile.from(vague, UPTIME).motion();

        assertThat(motion.fromSpeed()).isFalse();
        assertThat(motion.netDisplacementMeters()).isCloseTo(166.7, within(0.5));
        assertThat(motion.noiseFloorMeters(3.0, 2.0)).isEqualTo(100_000.0);
        assertThat(motion.movedBeyondNoise(3.0, 2.0)).isFalse();
    }

    @Test
    void reportsUnknownWithoutTwoUsableFixes() {
        assertThat(MotionProfile.from(List.of(), UPTIME).motion().isKnown()).isFalse();
        assertThat(MotionProfile.from(null, UPTIME).isUsable()).isFalse();
        assertThat(MotionProfile.from(steadyDrive(10.0, 1), UPTIME).motion().isKnown()).isFalse();
    }

    private static List<LocationSample> steadyDrive(double metersPerSecond, int fixes) {
        return steadyDrive(metersPerSecond, fixes, 5.0);
    }

    private static List<LocationSample> steadyDrive(
            double metersPerSecond, int fixes, double accuracy) {
        List<LocationSample> samples = new ArrayList<>(fixes);
        for (int i = 0; i < fixes; i++) {
            samples.add(fix(
                    UPTIME + seconds(i),
                    BASE_LAT + metresNorth(metersPerSecond * i),
                    BASE_LON,
                    accuracy,
                    metersPerSecond));
        }
        return samples;
    }

    private static EncodedFrameTimestamp frameAt(long presentationTimeNanos) {
        return new EncodedFrameTimestamp(0, presentationTimeNanos, false);
    }

    private static LocationSample fix(
            long monotonicNanos, double latitude, double longitude, double accuracy, Double speed) {
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
