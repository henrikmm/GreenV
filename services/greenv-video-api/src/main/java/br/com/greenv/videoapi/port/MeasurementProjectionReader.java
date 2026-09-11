package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.MeasurementProjection;

/**
 * Turns the two documents worker 2 leaves in object storage into the summary a map can draw.
 *
 * <p>Behind a port because it is JSON parsing, and a service that parses its own JSON is a service
 * that cannot be tested without one. The result is derived every time rather than trusted from the
 * announcement: the packet is the record, and a projection recomputed from it can be repaired by
 * re-reading storage instead of by measuring the segment again.
 */
public interface MeasurementProjectionReader {

    /**
     * @param resultPacket {@code measurement-result-v1.json}, which carries the camera positions
     *     and the quality summary; null or unreadable yields {@link MeasurementProjection#EMPTY}
     * @param assessment {@code assessment.json}, which carries the per-cell heights; null when it
     *     could not be read, in which case the geometry is still projected and the heights are not
     */
    MeasurementProjection project(byte[] resultPacket, byte[] assessment);
}
