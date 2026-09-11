package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import java.util.List;

/**
 * Draws a session: where the camera went, and the strip the measurement actually covered.
 *
 * <p>A writer rather than a response record because the output is GeoJSON, which map libraries
 * consume directly and which no Java record shape improves. Keeping it behind a port also keeps
 * the service free of JSON, the same way the readers do.
 */
public interface SegmentTrackWriter {

    /**
     * A {@code FeatureCollection} with up to two features per segment: the camera path as a
     * {@code LineString}, and the measured band as a {@code Polygon}. A segment with no track
     * contributes nothing rather than an empty geometry.
     */
    byte[] featureCollection(List<CaptureSegmentDocument> segments);
}
