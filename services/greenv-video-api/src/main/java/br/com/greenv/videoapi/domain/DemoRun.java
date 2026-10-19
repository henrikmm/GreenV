package br.com.greenv.videoapi.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * What the public site needs to draw one capture: its frames and its reconstruction.
 *
 * <p>Every URL is signed when this is built, from object keys, so a link is never older than the
 * request that returned it. The handler's own links last twelve hours and are deliberately not
 * kept. {@code frames} is empty until the frames are in storage, {@code manifest} null until the
 * capture is {@code fetched}.
 */
public record DemoRun(List<String> frames, JsonNode manifest, String gpu, Double gpuSeconds) {

    public DemoRun {
        frames = List.copyOf(frames);
    }
}
