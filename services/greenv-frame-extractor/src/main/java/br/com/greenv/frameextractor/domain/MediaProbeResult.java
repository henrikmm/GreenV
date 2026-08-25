package br.com.greenv.frameextractor.domain;

public record MediaProbeResult(
        double durationSeconds,
        double nativeFps,
        int width,
        int height,
        int rotation,
        int storedWidth,
        int storedHeight) {
}
