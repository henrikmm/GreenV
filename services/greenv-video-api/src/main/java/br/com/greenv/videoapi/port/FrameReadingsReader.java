package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.FrameReadings;

/** Reads one frame's votes out of the assessment Verge Studio wrote. */
public interface FrameReadingsReader {

    /**
     * @param assessment the bytes of {@code assessment.json}
     * @param canonicalFrame the number in the JPEG's file name
     * @return the frame's contribution, or {@link FrameReadings#EMPTY} when the assessment is
     *     unreadable or names no vote for it. Never throws: a photograph is worth showing even
     *     when nothing can be said about what it measured.
     */
    FrameReadings read(byte[] assessment, int canonicalFrame);
}
