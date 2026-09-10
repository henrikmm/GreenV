package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import br.com.greenv.frameextractor.domain.LocationSample;
import br.com.greenv.frameextractor.domain.SegmentMotion;
import java.util.ArrayList;
import java.util.List;

/**
 * Cumulative distance against time, so a frame can be placed on the road rather than on a clock.
 *
 * <p>Built by integrating {@code speedMetersPerSecond} where it is available. GNSS speed is derived
 * from Doppler shift, not from differencing positions, so it is far more precise than the phone's
 * own {@code distanceFromSessionStartMeters} — which is a running sum of great-circle hops and
 * therefore accumulates fix noise instead of cancelling it. A parked phone with 5 m fixes sums tens
 * of metres of path that never happened. The sum is kept only as a fallback for the case where the
 * platform reports no speed at all: Android returns {@code 0.0} when the provider has none, which is
 * indistinguishable from a genuine standstill.
 */
public final class MotionProfile {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /** Below this, a speed reading is treated as absent rather than as "stopped". */
    private static final double SPEED_EPSILON = 0.01;

    private final long[] times;
    private final double[] distances;
    private final SegmentMotion motion;

    private MotionProfile(long[] times, double[] distances, SegmentMotion motion) {
        this.times = times;
        this.distances = distances;
        this.motion = motion;
    }

    /**
     * Builds the profile on the segment's own clock.
     *
     * <p>A fix carries the phone's uptime in nanoseconds, hundreds of billions of them, while an
     * encoded frame's presentation time starts at zero. Keeping the fixes on the uptime clock put
     * every frame before the first fix, so every frame took distance zero, every group collapsed to
     * one viewpoint and no segment could ever be cut into groups — observed across every capture
     * between 7 and 9 September 2026. Rebasing here, and taking the frame itself in
     * {@link #distanceAt(EncodedFrameTimestamp)} rather than a bare long, is what stops the two
     * clocks being mixed again.
     */
    public static MotionProfile from(List<LocationSample> locations, long monotonicStartNanos) {
        List<LocationSample> usable = usable(locations);
        if (usable.size() < 2) {
            return new MotionProfile(new long[0], new double[0], SegmentMotion.UNKNOWN);
        }

        boolean fromSpeed = usable.stream()
                .anyMatch(fix -> fix.speedMetersPerSecond() != null
                        && fix.speedMetersPerSecond() > SPEED_EPSILON);

        long[] times = new long[usable.size()];
        double[] distances = new double[usable.size()];
        double travelled = 0;
        for (int i = 0; i < usable.size(); i++) {
            LocationSample fix = usable.get(i);
            times[i] = fix.monotonicNanos() - monotonicStartNanos;
            if (i > 0) {
                travelled += fromSpeed
                        ? integrateSpeed(usable.get(i - 1), fix)
                        : greatCircleMeters(usable.get(i - 1), fix);
            }
            distances[i] = travelled;
        }

        var first = usable.getFirst();
        var last = usable.getLast();
        return new MotionProfile(
                times,
                distances,
                new SegmentMotion(
                        travelled,
                        greatCircleMeters(first, last),
                        medianAccuracy(usable),
                        usable.size(),
                        fromSpeed));
    }

    public SegmentMotion motion() {
        return motion;
    }

    /**
     * Whether there are enough fixes to judge the camera's motion at all.
     *
     * <p>Deliberately not "did it move". A parked vehicle has perfectly good telemetry that says
     * zero, and that is an answer — conflating it with having no telemetry would send a stationary
     * capture down the fallback path and publish frames of one viewpoint.
     */
    public boolean isUsable() {
        return times.length >= 2;
    }

    /**
     * Seconds between the first and last usable fix: how much of the segment the fixes cover.
     *
     * <p>A verdict on the camera's motion only applies to the stretch the fixes span. Two fixes
     * 19 ms apart inside a 9.95 s segment describe 0.2% of it and say nothing about the rest.
     */
    public double fixSpanSeconds() {
        return times.length < 2 ? 0 : (times[times.length - 1] - times[0]) / 1_000_000_000.0;
    }

    /**
     * Distance travelled by the time this frame was captured, interpolated between fixes and
     * clamped at both ends. Frames outside the fix range — the camera starts before the first fix
     * arrives — take the nearest endpoint rather than an extrapolation nobody measured.
     */
    public double distanceAt(EncodedFrameTimestamp frame) {
        long at = frame.presentationTimeNanos();
        if (times.length == 0) {
            return 0;
        }
        if (at <= times[0]) {
            return distances[0];
        }
        if (at >= times[times.length - 1]) {
            return distances[distances.length - 1];
        }
        int high = 1;
        while (high < times.length && times[high] < at) {
            high++;
        }
        int low = high - 1;
        long span = times[high] - times[low];
        if (span <= 0) {
            return distances[low];
        }
        double fraction = (double) (at - times[low]) / span;
        return distances[low] + fraction * (distances[high] - distances[low]);
    }

    /** Trapezoid over the interval: speed is sampled at the endpoints, not constant across it. */
    private static double integrateSpeed(LocationSample from, LocationSample to) {
        double seconds = (to.monotonicNanos() - from.monotonicNanos()) / 1_000_000_000.0;
        if (seconds <= 0) {
            return 0;
        }
        double a = from.speedMetersPerSecond() == null ? 0 : Math.max(0, from.speedMetersPerSecond());
        double b = to.speedMetersPerSecond() == null ? 0 : Math.max(0, to.speedMetersPerSecond());
        return (a + b) / 2.0 * seconds;
    }

    static double greatCircleMeters(LocationSample from, LocationSample to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(to.longitude() - from.longitude());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Fixes must be time-ordered and finite; the associator already assumes the same of them. */
    private static List<LocationSample> usable(List<LocationSample> locations) {
        if (locations == null) {
            return List.of();
        }
        List<LocationSample> ordered = new ArrayList<>(locations.size());
        long previous = Long.MIN_VALUE;
        for (LocationSample fix : locations) {
            if (fix == null
                    || !Double.isFinite(fix.latitude())
                    || !Double.isFinite(fix.longitude())
                    || fix.monotonicNanos() < previous) {
                continue;
            }
            ordered.add(fix);
            previous = fix.monotonicNanos();
        }
        return ordered;
    }

    private static double medianAccuracy(List<LocationSample> fixes) {
        double[] values = fixes.stream()
                .mapToDouble(LocationSample::horizontalAccuracyMeters)
                .filter(Double::isFinite)
                .sorted()
                .toArray();
        if (values.length == 0) {
            return 0;
        }
        int middle = values.length / 2;
        return values.length % 2 == 1
                ? values[middle]
                : (values[middle - 1] + values[middle]) / 2.0;
    }
}
