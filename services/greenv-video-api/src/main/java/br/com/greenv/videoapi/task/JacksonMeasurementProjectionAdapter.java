package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.MeasurementProjection;
import br.com.greenv.videoapi.port.MeasurementProjectionReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads worker 2's two documents and keeps the handful of values a map needs.
 *
 * <p>Nothing here fails a measurement. A packet that cannot be parsed still happened, and the
 * segment is still measured: the projection comes back empty and the row records the measurement
 * without a summary, which a later re-read can fill in. Throwing instead would put a message that
 * already cost GPU time into the poison queue over a JSON field.
 */
@Component
public class JacksonMeasurementProjectionAdapter implements MeasurementProjectionReader {

    private static final Logger log = LoggerFactory.getLogger(JacksonMeasurementProjectionAdapter.class);

    /** Worst first, so the track reports the weakest fix that went into it. */
    private static final List<String> QUALITY_WORST_FIRST = List.of("unavailable", "degraded", "good");

    /**
     * The percentile of the measured cells that stands for the stretch.
     *
     * <p>0.95, and back to it on 14 September 2026 after a few hours at 0.90. Dropping it was a
     * way of surviving the guardrail: the top twentieth of a driven corridor's cells was the last
     * half metre against the rail, and that alone held 20 of 40 stretches at level 3 over grass of
     * 2 to 18 cm. The measurement stopped measuring the rail that same day, and with it gone the
     * two percentiles tell the same story — across the 43 segments of 13 September the gap between
     * them is 0 to 6 cm, usually 2, and no stretch reaches level 3 at either. So the aggregate goes
     * back to the one that is actually about mowing: a stretch whose worst twentieth has grown is a
     * stretch a crew is sent to.
     *
     * <p>The projection's field and column keep the name {@code extent95P95M}: the installed
     * capture app reads it, and a rename is a release, not a constant.
     */
    private static final double STRETCH_PERCENTILE = 0.95;

    private final ObjectMapper objectMapper;

    public JacksonMeasurementProjectionAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public MeasurementProjection project(byte[] resultPacket, byte[] assessment) {
        if (resultPacket == null || resultPacket.length == 0) {
            return MeasurementProjection.EMPTY;
        }
        try {
            JsonNode packet = objectMapper.readTree(new String(resultPacket, StandardCharsets.UTF_8));
            JsonNode quality = packet.path("measurement").path("quality");
            Track track = readTrack(packet.path("positions"));
            Double p95 = null;
            Double max = null;
            if (assessment != null && assessment.length > 0) {
                List<Double> heights = readMeasuredHeights(assessment);
                p95 = percentile(heights, STRETCH_PERCENTILE);
                max = heights.isEmpty() ? null : heights.get(heights.size() - 1);
            }
            return new MeasurementProjection(
                    p95,
                    max,
                    // From a high percentile rather than the tallest cell. One reading of 3.78 m
                    // among six is a bush or a tree the mask let through, and colouring a whole
                    // stretch by it sends a crew to mow something that is not grass.
                    MeasurementProjection.levelFor(p95, integer(quality, "measuredCells")),
                    integer(quality, "measuredCells"),
                    integer(quality, "abstainedCells"),
                    decimal(quality, "observedCellCoverage"),
                    track.centreLat(),
                    track.centreLon(),
                    track.geoJson(),
                    track.lengthM(),
                    track.quality());
        } catch (RuntimeException failure) {
            log.warn("Could not project the measurement packet: {}", failure.getMessage());
            return MeasurementProjection.EMPTY;
        }
    }

    /**
     * The camera path, which is the only geo-referenced thing a measurement produces.
     *
     * <p>Positions without a fix are skipped rather than interpolated: a gap in the track is the
     * truth about where the phone knew it was. Consecutive duplicates are dropped because a
     * stationary phone repeats one coordinate for as many frames as it recorded.
     */
    private Track readTrack(JsonNode positions) {
        if (!positions.isArray() || positions.isEmpty()) {
            return Track.EMPTY;
        }
        List<double[]> points = new ArrayList<>();
        double latitudeSum = 0;
        double longitudeSum = 0;
        int worst = QUALITY_WORST_FIRST.size();
        for (JsonNode position : positions) {
            int rank = QUALITY_WORST_FIRST.indexOf(position.path("locationQuality").asString(""));
            if (rank >= 0) {
                worst = Math.min(worst, rank);
            }
            Double latitude = decimal(position, "latitude");
            Double longitude = decimal(position, "longitude");
            if (latitude == null || longitude == null) {
                continue;
            }
            latitudeSum += latitude;
            longitudeSum += longitude;
            double[] previous = points.isEmpty() ? null : points.get(points.size() - 1);
            if (previous == null || previous[0] != longitude || previous[1] != latitude) {
                points.add(new double[] {longitude, latitude});
            }
        }
        if (points.isEmpty()) {
            return new Track(null, null, null, null, quality(worst));
        }
        int located = 0;
        for (JsonNode position : positions) {
            if (decimal(position, "latitude") != null) {
                located++;
            }
        }
        StringBuilder coordinates = new StringBuilder("{\"type\":\"LineString\",\"coordinates\":[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                coordinates.append(',');
            }
            coordinates.append('[').append(points.get(i)[0]).append(',').append(points.get(i)[1]).append(']');
        }
        coordinates.append("]}");
        // A single distinct point is a position, not a path, and GeoJSON requires two vertices in
        // a LineString. The centre still answers "where", which is what a pin needs.
        String geoJson = points.size() >= 2 ? coordinates.toString() : null;
        return new Track(
                latitudeSum / located, longitudeSum / located, geoJson, lengthOf(points), quality(worst));
    }

    /** Every measured cell's height above its own local ground, ascending. */
    private List<Double> readMeasuredHeights(byte[] assessment) {
        JsonNode root = objectMapper.readTree(new String(assessment, StandardCharsets.UTF_8));
        List<Double> heights = new ArrayList<>();
        for (JsonNode cell : root.path("assessment").path("measurements")) {
            if (!"measured".equals(cell.path("status").asString(""))) {
                continue;
            }
            Double extent = decimal(cell, "extent95M");
            if (extent != null) {
                heights.add(extent);
            }
        }
        heights.sort(Double::compareTo);
        return heights;
    }

    private static Double percentile(List<Double> ascending, double fraction) {
        if (ascending.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(fraction * ascending.size()) - 1;
        return ascending.get(Math.max(0, Math.min(index, ascending.size() - 1)));
    }

    private static String quality(int worstRank) {
        return worstRank < QUALITY_WORST_FIRST.size() ? QUALITY_WORST_FIRST.get(worstRank) : null;
    }

    private static Double decimal(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Integer integer(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private record Track(
            Double centreLat, Double centreLon, String geoJson, Double lengthM, String quality) {
        static final Track EMPTY = new Track(null, null, null, null, null);
    }

    private static final double EARTH_RADIUS_METRES = 6_371_000;

    /**
     * The length of the path on the ground, walked vertex to vertex.
     *
     * <p>The only length this system has. The order's area used to assume 150 metres a segment;
     * against the tracks on file that is fifteen times a walking capture and about right for a
     * car, which is to say it was a number rather than a measurement.
     *
     * <p>Haversine rather than a planar approximation. It is four trigonometric calls on a
     * handful of vertices, and it stays right at any latitude instead of only near the equator.
     *
     * <p>Under-reports a curve when the fixes are sparse, and that is the safe direction: the
     * straight line between two fixes is the shortest road that could join them.
     */
    private static Double lengthOf(List<double[]> points) {
        if (points.size() < 2) {
            return null;
        }
        double metres = 0;
        for (int i = 1; i < points.size(); i++) {
            metres += distance(points.get(i - 1), points.get(i));
        }
        return metres;
    }

    private static double distance(double[] from, double[] to) {
        double lat1 = Math.toRadians(from[1]);
        double lat2 = Math.toRadians(to[1]);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to[0] - from[0]);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
