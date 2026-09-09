package br.com.greenv.videoapi.domain;

import java.util.UUID;

public final class CaptureObjectKeys {

    private CaptureObjectKeys() {
    }

    public static String segmentPrefix(UUID sessionId, int segmentIndex) {
        if (sessionId == null || segmentIndex < 0) {
            throw new IllegalArgumentException("session id and non-negative segment index are required");
        }
        return "capture-sessions/%s/segments/%08d".formatted(sessionId, segmentIndex);
    }

    public static String video(UUID sessionId, int segmentIndex) {
        return segmentPrefix(sessionId, segmentIndex) + "/source.mp4";
    }

    public static String telemetry(UUID sessionId, int segmentIndex) {
        return segmentPrefix(sessionId, segmentIndex) + "/telemetry.json";
    }

    public static String manifest(UUID sessionId, int segmentIndex) {
        return segmentPrefix(sessionId, segmentIndex) + "/segment-manifest-v2.json";
    }

    /**
     * Where worker 2 leaves its packet.
     *
     * <p>Derived here rather than taken from the announcement it sends. The message arrives from a
     * broker, and a key built from a value on the wire is a key an attacker chooses; this one is
     * built from the identity the API already knows.
     */
    public static String measurement(UUID sessionId, int segmentIndex) {
        return segmentPrefix(sessionId, segmentIndex) + "/measurement/measurement-result-v1.json";
    }
}
