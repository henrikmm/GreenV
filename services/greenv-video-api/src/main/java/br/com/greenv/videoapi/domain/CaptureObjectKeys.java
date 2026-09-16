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
        return measurement(sessionId, segmentIndex, null);
    }

    /** The same envelope, for one window of the segment. */
    public static String measurement(UUID sessionId, int segmentIndex, Integer windowIndex) {
        return measurementArtifact(sessionId, segmentIndex, windowIndex, "measurement-result-v1.json");
    }

    /**
     * One file of worker 2's packet: the envelope above, {@code assessment.json}, {@code
     * report.html} or {@code SHA256SUMS}.
     *
     * <p>The name is a caller's word, so it is checked here rather than trusted: a segment
     * separator or a parent reference would address another session's objects.
     */
    public static String measurementArtifact(UUID sessionId, int segmentIndex, String fileName) {
        return measurementArtifact(sessionId, segmentIndex, null, fileName);
    }

    /**
     * One file of the packet a single window produced.
     *
     * <p>A segment used to produce exactly one measurement and it lived at {@code measurement/}.
     * A segment is now a row of windows, each its own reconstruction and its own reading, and each
     * gets a directory of its own. A window index of null keeps the old path, so every packet
     * measured before the change is still exactly where its row says it is. This mirrors
     * {@code measurementPrefix} in {@code services/greenv-measurement-worker/src/keys.mjs}, which
     * is what actually writes the objects; the two padding rules must stay identical.
     */
    public static String measurementArtifact(
            UUID sessionId, int segmentIndex, Integer windowIndex, String fileName) {
        return measurementPrefix(sessionId, segmentIndex, windowIndex) + "/" + safeFileName(fileName);
    }

    /** The directory a window's packet lives in: {@code .../measurement/w03}. */
    public static String measurementPrefix(UUID sessionId, int segmentIndex, Integer windowIndex) {
        String prefix = segmentPrefix(sessionId, segmentIndex) + "/measurement";
        if (windowIndex == null) {
            return prefix;
        }
        if (windowIndex < 0) {
            throw new IllegalArgumentException("window index must be non-negative");
        }
        return prefix + "/w" + "%02d".formatted(windowIndex);
    }

    /** One sampled frame. Same rule as above, and the same reason. */
    public static String sampledFrame(UUID sessionId, int segmentIndex, String fileName) {
        return segmentPrefix(sessionId, segmentIndex) + "/sampled-frames/" + safeFileName(fileName);
    }

    /**
     * One file of the reconstruction the depth service left beside the frames.
     *
     * <p>Under the run that computed it, because a segment measured twice has two of them and the
     * run id is the only thing that tells them apart. This is the shape the measurement worker
     * writes (`services/greenv-measurement-worker/src/keys.mjs`, `depthArtifact`) and reads back
     * when it re-measures without waking a GPU.
     */
    public static String depthArtifact(UUID sessionId, int segmentIndex, String runId, String fileName) {
        return segmentPrefix(sessionId, segmentIndex)
                + "/depth/" + safeFileName(runId) + "/" + safeFileName(fileName);
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new IllegalArgumentException("object file name must be a plain name");
        }
        return fileName;
    }
}
