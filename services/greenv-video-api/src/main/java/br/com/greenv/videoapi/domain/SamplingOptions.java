package br.com.greenv.videoapi.domain;

public record SamplingOptions(double requestedFps, int maxFrames, int longEdge) {

    public static final double DEFAULT_FPS = 10.0;
    public static final int DEFAULT_MAX_FRAMES = 112;
    public static final int DEFAULT_LONG_EDGE = 1024;
}
