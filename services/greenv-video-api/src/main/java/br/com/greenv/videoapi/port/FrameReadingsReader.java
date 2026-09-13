package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.FrameReadings;
import java.util.List;

/** Turns the assessment Verge Studio wrote into one row per frame. */
public interface FrameReadingsReader {

    /**
     * Every frame's contribution, from one pass over the assessment.
     *
     * <p>All frames at once rather than one at a time: the assessment is organised by cell, so
     * answering for a single frame costs the same walk as answering for all of them. Doing it
     * once at record time is what lets the route read a row instead of an object.
     *
     * @param assessment the bytes of {@code assessment.json}
     * @return one entry per frame that appears in any vote, ordered by canonical frame. Empty
     *     when the assessment is missing or unreadable — never throws, because a photograph is
     *     worth showing even when nothing can be said about what it measured.
     */
    List<FrameReadings> readAll(byte[] assessment);
}
