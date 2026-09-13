package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.SegmentPlace;

/**
 * Turns a coordinate into a street name.
 *
 * <p>An outbound port because the answer comes from outside: today OpenStreetMap's Nominatim,
 * tomorrow whatever a concession already pays for. The service that uses it must treat a null as
 * an ordinary outcome — the network is down, the service is rate limiting, the point is in the
 * sea — and never let it fail anything that matters.
 */
public interface PlaceNameResolver {

    /**
     * @return the place, or null when it could not be resolved right now. A resolver that
     *     successfully found nothing returns {@link SegmentPlace#unresolved}, which is a
     *     different answer: it means "do not ask again", where null means "ask later".
     */
    SegmentPlace resolve(double latitude, double longitude);
}
