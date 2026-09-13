package br.com.greenv.videoapi.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How far each segment of a session actually travelled.
 *
 * <p>The obvious answer is the length of the drawn track, and it is wrong by most of the
 * distance. The phone emits two to four distinct fixes in a ten-second segment and they cluster,
 * so the polyline spans 7.7 to 10.7 metres — while the same session's segments start 67 to 103
 * metres apart, which is 24 to 37 km/h and the speed of the car that recorded it. The drawn track
 * is a sample of the path, not the path.
 *
 * <p>So the distance is taken between segments rather than inside one. Consecutive centres are a
 * real ground distance over a real interval, and the pair gives a speed; that speed against the
 * segment's own duration gives its length. It survives a gap in the indices, because the interval
 * is read from the timestamps and not assumed to be one segment long.
 *
 * <p>The last segment of a session has nothing after it, and takes the median speed of the ones
 * that do. A session with a single located segment has no speed at all and gets nothing — null,
 * not a guess dressed as a measurement.
 */
public final class SegmentTravel {

    private static final double EARTH_RADIUS_METRES = 6_371_000;

    /** Above this, a pair of fixes is noise rather than a vehicle. 150 km/h on a mowing survey. */
    private static final double IMPLAUSIBLE_SPEED_MS = 41.7;

    private SegmentTravel() {}

    /**
     * One point of a session's path, as the projection left it.
     *
     * @param centreLat null when the segment recorded no fix, and then it takes part in nothing
     */
    public record Fix(
            int segmentIndex, Double centreLat, Double centreLon, Instant capturedAt, long durationMillis) {

        boolean located() {
            return centreLat != null && centreLon != null && capturedAt != null && durationMillis > 0;
        }
    }

    /** Segment index to metres travelled. Absent where no speed could be established. */
    public static Map<Integer, Double> lengths(List<Fix> fixes) {
        List<Fix> located = fixes.stream().filter(Fix::located).sorted(byIndex()).toList();
        if (located.size() < 2) {
            return Map.of();
        }

        Map<Integer, Double> speeds = new HashMap<>();
        for (int i = 0; i < located.size() - 1; i++) {
            Fix from = located.get(i);
            Fix to = located.get(i + 1);
            double seconds = Duration.between(from.capturedAt(), to.capturedAt()).toMillis() / 1000.0;
            if (seconds <= 0) {
                continue;
            }
            double speed = distanceMetres(from, to) / seconds;
            if (speed <= IMPLAUSIBLE_SPEED_MS) {
                speeds.put(from.segmentIndex(), speed);
            }
        }
        if (speeds.isEmpty()) {
            return Map.of();
        }

        double fallback = median(new ArrayList<>(speeds.values()));
        Map<Integer, Double> lengths = new HashMap<>();
        for (Fix fix : located) {
            double speed = speeds.getOrDefault(fix.segmentIndex(), fallback);
            lengths.put(fix.segmentIndex(), speed * (fix.durationMillis() / 1000.0));
        }
        return Map.copyOf(lengths);
    }

    private static java.util.Comparator<Fix> byIndex() {
        return java.util.Comparator.comparingInt(Fix::segmentIndex);
    }

    /** Haversine: four trig calls, and right at any latitude rather than only near the equator. */
    private static double distanceMetres(Fix from, Fix to) {
        double lat1 = Math.toRadians(from.centreLat());
        double lat2 = Math.toRadians(to.centreLat());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.centreLon() - from.centreLon());
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Median and not mean: one bad fix pair should move the fallback by nothing. */
    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
