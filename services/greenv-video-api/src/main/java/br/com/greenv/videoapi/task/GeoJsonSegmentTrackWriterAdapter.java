package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.MeasurementProjection;
import br.com.greenv.videoapi.port.SegmentTrackWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The session's shape, as GeoJSON.
 *
 * <p>Two features per measured stretch, which is a window of a segment wherever the segment was
 * cut into windows and the segment itself where it was not. The {@code LineString} is where the
 * camera went over that stretch, straight from its own stored track. The {@code Polygon} is that
 * line widened to the band the grid actually measured — {@code band.maxDistanceFromRoadM} is 5 m
 * in every packet — so the filled area on a map is the area a reading can speak for, and not a
 * guess at how much verge there was.
 *
 * <p>The band is drawn on both sides because the packet folds them together:
 * {@code corridor.side} is {@code "unsigned-both-sides-folded"}, so which side of the road a cell
 * sat on was never recorded. Drawing one side would be choosing one.
 */
@Component
public class GeoJsonSegmentTrackWriterAdapter implements SegmentTrackWriter {

    /** {@code band.maxDistanceFromRoadM} in every assessment Verge Studio has produced. */
    private static final double BAND_HALF_WIDTH_M = 5.0;

    private static final double METRES_PER_DEGREE_LATITUDE = 111_320.0;

    private final ObjectMapper objectMapper;

    public GeoJsonSegmentTrackWriterAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] featureCollection(List<CaptureSegmentDocument> segments) {
        List<Object> features = new ArrayList<>();
        for (CaptureSegmentDocument segment : segments) {
            List<double[]> track = trackOf(segment);
            if (track.size() < 2) {
                continue;
            }
            Map<String, Object> properties = propertiesOf(segment);
            features.add(feature(geometry("LineString", track), merge(properties, "kind", "track")));
            features.add(feature(
                    Map.of("type", "Polygon", "coordinates", List.of(band(track))),
                    merge(properties, "kind", "band")));
        }
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("features", features);
        return objectMapper.writeValueAsString(collection).getBytes(StandardCharsets.UTF_8);
    }

    private List<double[]> trackOf(CaptureSegmentDocument segment) {
        String stored = segment.measurement() == null ? null : segment.measurement().trackGeoJson();
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            JsonNode coordinates = objectMapper.readTree(stored).path("coordinates");
            List<double[]> points = new ArrayList<>();
            for (JsonNode point : coordinates) {
                if (point.isArray() && point.size() >= 2) {
                    points.add(new double[] {point.get(0).asDouble(), point.get(1).asDouble()});
                }
            }
            return points;
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /**
     * The line widened by the band's half width, as one closed ring.
     *
     * <p>Offsets are computed in metres on an equirectangular approximation around the track's own
     * latitude, which is exact enough over the two hundred metres a segment can cover and avoids
     * a projection dependency. The ring runs up the left side and back down the right.
     */
    private static List<List<Double>> band(List<double[]> track) {
        double latitudeRadians = Math.toRadians(track.get(0)[1]);
        double metresPerDegreeLongitude = METRES_PER_DEGREE_LATITUDE * Math.cos(latitudeRadians);
        List<List<Double>> left = new ArrayList<>();
        List<List<Double>> right = new ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            double[] previous = track.get(Math.max(i - 1, 0));
            double[] next = track.get(Math.min(i + 1, track.size() - 1));
            double eastwards = (next[0] - previous[0]) * metresPerDegreeLongitude;
            double northwards = (next[1] - previous[1]) * METRES_PER_DEGREE_LATITUDE;
            double length = Math.hypot(eastwards, northwards);
            if (length == 0) {
                continue;
            }
            // Rotate the direction a quarter turn to get the normal, then step along it.
            double offsetEast = -northwards / length * BAND_HALF_WIDTH_M / metresPerDegreeLongitude;
            double offsetNorth = eastwards / length * BAND_HALF_WIDTH_M / METRES_PER_DEGREE_LATITUDE;
            double[] point = track.get(i);
            left.add(List.of(point[0] + offsetEast, point[1] + offsetNorth));
            right.add(List.of(point[0] - offsetEast, point[1] - offsetNorth));
        }
        List<List<Double>> ring = new ArrayList<>(left);
        for (int i = right.size() - 1; i >= 0; i--) {
            ring.add(right.get(i));
        }
        if (!ring.isEmpty()) {
            ring.add(ring.get(0));
        }
        return ring;
    }

    private static Map<String, Object> propertiesOf(CaptureSegmentDocument segment) {
        MeasurementProjection measurement = segment.measurement();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", segment.sessionId().toString());
        properties.put("segmentIndex", segment.segmentIndex());
        // Null means the whole segment was measured as one. A feature with a window index covers
        // about 25 m of road; one without it may cover two hundred, and a map that could not tell
        // them apart would draw two very different claims the same way.
        properties.put("windowIndex", segment.windowIndex());
        properties.put("windowStartMeters", segment.windowStartMeters());
        properties.put("windowEndMeters", segment.windowEndMeters());
        properties.put("capturedAt", segment.capturedAt() == null ? null : segment.capturedAt().toString());
        properties.put("measurementState", segment.measurementState());
        properties.put("measurementIsMock", segment.measurementIsMock());
        properties.put("level", measurement.level());
        properties.put("extent95P95M", measurement.extent95P95M());
        properties.put("extent95MaxM", measurement.extent95MaxM());
        properties.put("cellsMeasured", measurement.cellsMeasured());
        properties.put("cellsAbstained", measurement.cellsAbstained());
        properties.put("coverage", measurement.coverage());
        properties.put("locationQuality", measurement.trackLocationQuality());
        // Every automatic reading carries this, and a map that drops it turns evidence into an
        // instruction. See docs/AUTOMATIC-HEIGHT.md.
        properties.put("operationalStatus", "not-ready");
        return properties;
    }

    private static Map<String, Object> merge(Map<String, Object> properties, String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(properties);
        merged.put(key, value);
        return merged;
    }

    private static Map<String, Object> geometry(String type, List<double[]> points) {
        List<List<Double>> coordinates = new ArrayList<>();
        for (double[] point : points) {
            coordinates.add(List.of(point[0], point[1]));
        }
        return Map.of("type", type, "coordinates", coordinates);
    }

    private static Map<String, Object> feature(Map<String, Object> geometry, Map<String, Object> properties) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("geometry", geometry);
        feature.put("properties", properties);
        return feature;
    }
}
