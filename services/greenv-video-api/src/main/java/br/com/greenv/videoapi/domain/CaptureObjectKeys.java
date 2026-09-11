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
        return measurementArtifact(sessionId, segmentIndex, "measurement-result-v1.json");
    }

    /**
     * One file of worker 2's packet: the envelope above, {@code assessment.json}, {@code
     * report.html} or {@code SHA256SUMS}.
     *
     * <p>The name is a caller's word, so it is checked here rather than trusted: a segment
     * separator or a parent reference would address another session's objects.
     */
    public static String measurementArtifact(UUID sessionId, int segmentIndex, String fileName) {
        return segmentPrefix(sessionId, segmentIndex) + "/measurement/" + safeFileName(fileName);
    }

    /** One sampled frame. Same rule as above, and the same reason. */
    public static String sampledFrame(UUID sessionId, int segmentIndex, String fileName) {
        return segmentPrefix(sessionId, segmentIndex) + "/sampled-frames/" + safeFileName(fileName);
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new IllegalArgumentException("object file name must be a plain name");
        }
        return fileName;
    }
}
