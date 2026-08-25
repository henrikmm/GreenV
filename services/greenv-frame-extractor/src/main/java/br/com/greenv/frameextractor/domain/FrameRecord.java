package br.com.greenv.frameextractor.domain;

public record FrameRecord(
        int index,
        String fileName,
        double timestampSeconds,
        long sizeBytes,
        String sha256) {
}
