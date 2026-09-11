package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.domain.LocationSample;
import br.com.greenv.frameextractor.domain.RouteIdentity;
import br.com.greenv.frameextractor.domain.RouteIdentity.BearingSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Works out the road and the direction from the fixes, instead of asking the person driving.
 *
 * <p>Two independent questions with two different accuracy budgets, and separating them is what
 * makes the road answerable at all. <em>Naming</em> a road needs to be right to a few hundred
 * metres. <em>Placing a km</em> needs to be right to about ten. The same thirty markers that are
 * useless for the second are ample for the first, which is why this class assigns a rodovia and
 * still leaves km null.
 */
@Component
public class RouteIdentifier {

    private static final Logger log = LoggerFactory.getLogger(RouteIdentifier.class);

    /**
     * How far a track may sit from a road and still be called that road.
     *
     * <p>The reference line runs through km markers about a kilometre apart, so it cuts corners
     * the real carriageway follows; a tolerance under a few hundred metres would reject a capture
     * driven on the road itself. The four segments on record sit 13.15 km away and are rejected
     * by any tolerance worth having.
     */
    private static final double ROAD_TOLERANCE_METRES = 400.0;

    /** Below this the platform reports no usable course, which is why walking produces none. */
    private static final double COURSE_SPEED_FLOOR_MPS = 2.0;

    /**
     * Displacement has to beat the fixes' own accuracy by this much before its azimuth means
     * anything. The movement gate uses the same discipline for the same reason.
     */
    private static final double DISPLACEMENT_ACCURACY_MULTIPLE = 3.0;

    private static final double METRES_PER_DEGREE_LATITUDE = 111_320.0;

    private final List<Highway> highways;

    public RouteIdentifier(ObjectMapper objectMapper) {
        this.highways = load(objectMapper);
    }

    /**
     * @param motion the same profile the sampling uses, for the displacement and the accuracy it
     *     has to beat
     */
    public RouteIdentity identify(List<LocationSample> fixes, br.com.greenv.frameextractor.domain.SegmentMotion motion) {
        if (fixes == null || fixes.isEmpty()) {
            return RouteIdentity.UNKNOWN;
        }
        Bearing bearing = bearingOf(fixes, motion);
        Match road = nearestRoad(fixes);
        return new RouteIdentity(
                road == null ? null : road.rodovia(),
                bearing == null ? null : cardinalOf(bearing.degrees()),
                bearing == null ? null : bearing.degrees(),
                bearing == null ? BearingSource.NONE : bearing.source(),
                road == null ? null : road.metres());
    }

    /**
     * Doppler course first, displacement second, nothing third.
     *
     * <p>Courses are averaged as vectors rather than as numbers: the mean of 350 and 10 degrees
     * is 0, not 180, and a road running north would otherwise come back as running south.
     */
    private Bearing bearingOf(List<LocationSample> fixes, br.com.greenv.frameextractor.domain.SegmentMotion motion) {
        double east = 0;
        double north = 0;
        int courses = 0;
        for (LocationSample fix : fixes) {
            Double course = fix.courseDegrees();
            Double speed = fix.speedMetersPerSecond();
            // The platform writes a negative course when it has none, and a course reported while
            // barely moving is the direction of GNSS noise.
            if (course == null || course < 0 || speed == null || speed < COURSE_SPEED_FLOOR_MPS) {
                continue;
            }
            double radians = Math.toRadians(course);
            east += Math.sin(radians);
            north += Math.cos(radians);
            courses++;
        }
        if (courses > 0 && (east != 0 || north != 0)) {
            return new Bearing(normalise(Math.toDegrees(Math.atan2(east, north))), BearingSource.DOPPLER_COURSE);
        }

        LocationSample first = fixes.get(0);
        LocationSample last = fixes.get(fixes.size() - 1);
        double floor = Math.max(motion.medianHorizontalAccuracyMeters() * DISPLACEMENT_ACCURACY_MULTIPLE, 25.0);
        if (motion.netDisplacementMeters() < floor) {
            return null;
        }
        return new Bearing(azimuth(first, last), BearingSource.DISPLACEMENT);
    }

    /** The four-value vocabulary the database enforces, so a bearing folds into the nearest one. */
    private static String cardinalOf(double degrees) {
        if (degrees >= 315 || degrees < 45) {
            return "norte";
        }
        if (degrees < 135) {
            return "leste";
        }
        return degrees < 225 ? "sul" : "oeste";
    }

    private Match nearestRoad(List<LocationSample> fixes) {
        double latitude = fixes.stream().mapToDouble(LocationSample::latitude).average().orElse(0);
        double longitude = fixes.stream().mapToDouble(LocationSample::longitude).average().orElse(0);
        Match best = null;
        for (Highway highway : highways) {
            double metres = highway.distanceMetres(latitude, longitude);
            if (best == null || metres < best.metres()) {
                best = new Match(highway.rodovia(), metres);
            }
        }
        if (best == null) {
            return null;
        }
        if (best.metres() > ROAD_TOLERANCE_METRES) {
            log.debug("Track sits {} m from the nearest road in the reference; naming none", (long) best.metres());
            return null;
        }
        return best;
    }

    private static double azimuth(LocationSample from, LocationSample to) {
        double fromLatitude = Math.toRadians(from.latitude());
        double toLatitude = Math.toRadians(to.latitude());
        double deltaLongitude = Math.toRadians(to.longitude() - from.longitude());
        double y = Math.sin(deltaLongitude) * Math.cos(toLatitude);
        double x = Math.cos(fromLatitude) * Math.sin(toLatitude)
                - Math.sin(fromLatitude) * Math.cos(toLatitude) * Math.cos(deltaLongitude);
        return normalise(Math.toDegrees(Math.atan2(y, x)));
    }

    /**
     * Into [0, 360).
     *
     * <p>The second modulo is not redundant. Due north computed as a vector mean comes out as a
     * tiny negative number, and adding 360 to it lands on exactly 360.0 once the floating point
     * absorbs the remainder — a bearing outside the range it claims to be in.
     */
    private static double normalise(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    private static List<Highway> load(ObjectMapper objectMapper) {
        try (InputStream stream = new ClassPathResource("reference/highways.geojson").getInputStream()) {
            JsonNode root = objectMapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            List<Highway> loaded = new ArrayList<>();
            for (JsonNode feature : root.path("features")) {
                String rodovia = feature.path("properties").path("rodovia").asString("");
                List<double[]> vertices = new ArrayList<>();
                for (JsonNode point : feature.path("geometry").path("coordinates")) {
                    vertices.add(new double[] {point.get(1).asDouble(), point.get(0).asDouble()});
                }
                if (!rodovia.isBlank() && vertices.size() >= 2) {
                    loaded.add(new Highway(rodovia, vertices));
                }
            }
            return List.copyOf(loaded);
        } catch (IOException | RuntimeException absent) {
            // A deployment with no reference names no roads, which is the same answer it gives
            // for a capture nowhere near one. Failing to start over a display asset would be
            // worse than extracting frames without a road name.
            log.warn("No highway reference loaded, so no capture will be given a road: {}", absent.toString());
            return List.of();
        }
    }

    private record Bearing(double degrees, BearingSource source) {}

    private record Match(String rodovia, double metres) {}

    /** One road, as the polyline its km markers trace. */
    private record Highway(String rodovia, List<double[]> vertices) {

        double distanceMetres(double latitude, double longitude) {
            double metresPerDegreeLongitude = METRES_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude));
            double best = Double.MAX_VALUE;
            for (int i = 1; i < vertices.size(); i++) {
                best = Math.min(
                        best,
                        segmentDistance(
                                (longitude - vertices.get(i - 1)[1]) * metresPerDegreeLongitude,
                                (latitude - vertices.get(i - 1)[0]) * METRES_PER_DEGREE_LATITUDE,
                                (vertices.get(i)[1] - vertices.get(i - 1)[1]) * metresPerDegreeLongitude,
                                (vertices.get(i)[0] - vertices.get(i - 1)[0]) * METRES_PER_DEGREE_LATITUDE));
            }
            return best;
        }

        /** Point-to-segment distance, with the point already expressed relative to the start. */
        private static double segmentDistance(double pointEast, double pointNorth, double east, double north) {
            double lengthSquared = east * east + north * north;
            if (lengthSquared == 0) {
                return Math.hypot(pointEast, pointNorth);
            }
            double t = Math.max(0, Math.min(1, (pointEast * east + pointNorth * north) / lengthSquared));
            return Math.hypot(pointEast - t * east, pointNorth - t * north);
        }
    }
}
