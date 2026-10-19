package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a demo capture's timeline.
 *
 * <p>{@code tMs} is milliseconds since the capture was announced, on this server's clock only.
 * The phone's own clock appears in the metadata it sent and is never subtracted from ours: two
 * clocks a few hundred milliseconds apart would invent a network time.
 */
public record DemoPhase(String phase, Instant at, long tMs, Map<String, Object> detail) {

    public DemoPhase {
        detail = detail == null || detail.isEmpty()
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public static DemoPhase of(String phase, Instant createdAt, Instant now, Map<String, Object> detail) {
        return new DemoPhase(phase, now, Math.max(0, now.toEpochMilli() - createdAt.toEpochMilli()), detail);
    }
}
