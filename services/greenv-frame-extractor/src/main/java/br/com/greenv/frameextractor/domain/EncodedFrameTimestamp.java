package br.com.greenv.frameextractor.domain;

public record EncodedFrameTimestamp(int index, long presentationTimeNanos, boolean keyFrame) {
}
