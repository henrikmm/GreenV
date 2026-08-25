package br.com.greenv.frameextractor.domain;

public record LocationSample(
        long monotonicNanos,
        double latitude,
        double longitude,
        Double altitudeMeters,
        double horizontalAccuracyMeters,
        Double verticalAccuracyMeters,
        Double speedMetersPerSecond,
        Double speedAccuracyMetersPerSecond,
        Double courseDegrees,
        Double courseAccuracyDegrees,
        Double distanceFromSessionStartMeters) {
}
