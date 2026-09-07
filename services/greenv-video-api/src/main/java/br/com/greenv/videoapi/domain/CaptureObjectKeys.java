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
}
