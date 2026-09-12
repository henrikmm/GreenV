package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.RoadMarkers;
import br.com.greenv.videoapi.domain.SegmentPlace;
import br.com.greenv.videoapi.port.PlaceNameResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Street names from OpenStreetMap's Nominatim.
 *
 * <p>Zoom 18 rather than 17 because the house number only comes back at building level, and the
 * number is half of what makes the answer useful to a crew. What comes back is the nearest
 * addressable point, which on a verge is the building across the road — the API calls the field
 * approximate for that reason and the dashboard repeats it.
 *
 * <p>Nominatim's usage policy is the reason for three things here: a real {@code User-Agent} that
 * identifies this deployment and can be contacted, a caller that resolves at most one stretch per
 * second, and a cache — the row itself — so a coordinate is asked about once and never again.
 * A deployment that cannot meet those should set {@code greenv.places.enabled=false} and get
 * kilometre markers only.
 *
 * <p>The kilometre marker is computed here too, from the reference markers, so a caller gets one
 * place rather than two halves to join. It is bolted onto whatever Nominatim said: a reading on
 * the SP-021 gets both the street name and the marker.
 */
@Component
@ConditionalOnProperty(name = "greenv.places.enabled", havingValue = "true", matchIfMissing = true)
public class NominatimPlaceNameAdapter implements PlaceNameResolver {

    private static final Logger log = LoggerFactory.getLogger(NominatimPlaceNameAdapter.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper objectMapper;
    private final RoadMarkers markers;
    private final Clock clock;
    private final String endpoint;
    private final String userAgent;

    public NominatimPlaceNameAdapter(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${greenv.places.endpoint:https://nominatim.openstreetmap.org/reverse}")
                    String endpoint,
            @Value("${greenv.places.user-agent:GreenV/1.0 (roadside vegetation dashboard)}")
                    String userAgent) {
        this.objectMapper = objectMapper;
        this.markers = new RoadMarkers(objectMapper);
        this.clock = clock;
        this.endpoint = endpoint;
        this.userAgent = userAgent;
    }

    @Override
    public SegmentPlace resolve(double latitude, double longitude) {
        RoadMarkers.Nearest nearest = markers.nearest(latitude, longitude);
        JsonNode address = reverse(latitude, longitude);
        if (address == null) {
            // The lookup failed rather than found nothing. A marker alone is still an answer, and
            // returning it beats returning null and retrying a road that will not change.
            return nearest == null ? null : fromMarkerOnly(nearest);
        }

        String road = text(address, "road", "pedestrian", "footway", "residential");
        String area = text(address, "suburb", "neighbourhood", "city_district");
        String city = text(address, "city", "town", "municipality", "village");
        String houseNumber = text(address, "house_number");
        String label = road != null ? road : area != null ? area : city;

        if (label == null && nearest == null) {
            return SegmentPlace.unresolved(clock.instant());
        }

        String detail = join(road != null ? area : null, city);
        return new SegmentPlace(
                label,
                detail,
                houseNumber,
                nearest == null ? null : nearest.road(),
                nearest == null ? null : nearest.km(),
                nearest == null ? null : nearest.offsetMetres(),
                label == null ? "reference-markers" : "openstreetmap",
                clock.instant());
    }

    private SegmentPlace fromMarkerOnly(RoadMarkers.Nearest nearest) {
        return new SegmentPlace(
                null, null, null, nearest.road(), nearest.km(), nearest.offsetMetres(),
                "reference-markers", clock.instant());
    }

    private JsonNode reverse(double latitude, double longitude) {
        String url = String.format(
                Locale.ROOT,
                "%s?format=jsonv2&zoom=18&addressdetails=1&accept-language=pt-BR&lat=%.6f&lon=%.6f",
                endpoint, latitude, longitude);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("place lookup returned {} for {},{}", response.statusCode(), latitude, longitude);
                return null;
            }
            JsonNode body = objectMapper.readTree(response.body());
            return body.path("address");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unreachable) {
            log.warn("place lookup failed for {},{}: {}", latitude, longitude, unreachable.toString());
            return null;
        }
    }

    private static String text(JsonNode address, String... fields) {
        for (String field : fields) {
            JsonNode value = address.path(field);
            if (value.isString() && !value.asString().isBlank()) {
                return value.asString();
            }
        }
        return null;
    }

    private static String join(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append(" · ");
            }
            joined.append(part);
        }
        return joined.isEmpty() ? null : joined.toString();
    }
}
