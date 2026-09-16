package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.SampledFrame;
import java.util.List;

/**
 * Lists the frames a segment published, with the camera position for each one where it is known.
 *
 * <p>Two documents rather than one because they answer different questions. The manifest says
 * which images exist, and it is the only thing that does — a name that is not in it is not an
 * object this API will serve. The measurement packet says where the camera was, and it exists only
 * once a segment has been measured.
 */
public interface SampledFrameReader {

    /**
     * @param manifest {@code segment-manifest-v2.json}; without it there is nothing to list
     * @param resultPackets every {@code measurement-result-v1.json} covering this segment, which
     *     is one packet when it was measured whole and one per window otherwise. Empty when
     *     unmeasured, and then the frames come back without coordinates. A list rather than a
     *     single document because a window's packet carries only the positions of the frames that
     *     window was reconstructed from, and the segment's photographs are spread across all of
     *     them
     */
    List<SampledFrame> read(byte[] manifest, List<byte[]> resultPackets);
}
