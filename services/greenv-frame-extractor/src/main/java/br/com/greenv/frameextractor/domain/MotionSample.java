package br.com.greenv.frameextractor.domain;

public record MotionSample(
        long monotonicNanos,
        double quaternionX,
        double quaternionY,
        double quaternionZ,
        double quaternionW,
        double gravityX,
        double gravityY,
        double gravityZ,
        double userAccelerationX,
        double userAccelerationY,
        double userAccelerationZ,
        double rotationRateX,
        double rotationRateY,
        double rotationRateZ) {
}
