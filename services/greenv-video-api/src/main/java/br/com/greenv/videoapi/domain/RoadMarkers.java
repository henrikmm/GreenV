package br.com.greenv.videoapi.domain;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The concession's kilometre posts, as shipped with the service.
 *
 * <p>Thirty points along the SP-021, the same file the dashboard draws. They average 1066 metres
 * apart, and that number is the whole story: this can say which stretch of road a reading is on
 * and must never be read as where along it. A reading 400 m past the KM 12 post still comes back
 * as KM 12, and {@link Nearest#offsetMetres()} exists so a caller can see that rather than guess.
 *
 * <p>{@link #TOLERANCE_METRES} is generous for the same reason. Half a kilometre either side of a
 * post is still plausibly the same road when the posts are a kilometre apart; beyond that the
 * honest answer is that the capture was somewhere else, which is the answer every capture on
 * record gets today.
 *
 * <p>Not a Spring bean: {@code domain} holds what the system is about, and a class there that
 * only exists because a container wires it has already stopped being that.
 */
public class RoadMarkers {

    /** Beyond this, a marker says nothing: the capture was not on that road. */
    public static final double TOLERANCE_METRES = 500.0;

    private static final String RESOURCE = "/reference/marco_km.geojson";
    private static final double EARTH_RADIUS_METRES = 6_371_000.0;

    private final List<Marker> markers;

    public RoadMarkers(ObjectMapper objectMapper) {
        this.markers = load(objectMapper);
    }

    /** @return the nearest marker within {@link #TOLERANCE_METRES}, or null */
    public Nearest nearest(double latitude, double longitude) {
        Marker best = null;
        double bestMetres = Double.MAX_VALUE;
        for (Marker marker : markers) {
            double metres = distanceMetres(latitude, longitude, marker.latitude, marker.longitude);
            if (metres < bestMetres) {
                bestMetres = metres;
                best = marker;
            }
        }
        if (best == null || bestMetres > TOLERANCE_METRES) {
            return null;
        }
        return new Nearest(best.road, best.km, bestMetres);
    }

    private static List<Marker> load(ObjectMapper objectMapper) {
        try (InputStream stream = RoadMarkers.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            List<Marker> loaded = new ArrayList<>();
            for (JsonNode feature : root.path("features")) {
                JsonNode coordinates = feature.path("geometry").path("coordinates");
                if (coordinates.size() < 2) {
                    continue;
                }
                loaded.add(new Marker(
                        feature.path("properties").path("road").asString("SP-021"),
                        feature.path("properties").path("km").asInt(),
                        coordinates.get(1).asDouble(),
                        coordinates.get(0).asDouble()));
            }
            return List.copyOf(loaded);
        } catch (Exception unreadable) {
            // A missing reference file must not stop the service starting. Every reading simply
            // comes back without a kilometre, which is what happens off the reference road anyway.
            return List.of();
        }
    }

    private static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private record Marker(String road, int km, double latitude, double longitude) {}

    public record Nearest(String road, int km, double offsetMetres) {}
}
