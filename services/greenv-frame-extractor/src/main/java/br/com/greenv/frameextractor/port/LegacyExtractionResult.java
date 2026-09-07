package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.FrameManifest;

public record LegacyExtractionResult(
        String state,
        FrameManifest manifest,
        String errorCode,
        String errorMessage) {

    public static LegacyExtractionResult ready(FrameManifest manifest) {
        return new LegacyExtractionResult("frames_ready", manifest, null, null);
    }

    public static LegacyExtractionResult failed(String state, String errorCode, String errorMessage) {
        return new LegacyExtractionResult(state, null, errorCode, errorMessage);
    }

    public boolean isReady() {
        return manifest != null;
    }

    public boolean shouldRetry() {
        return "queued".equals(state);
    }
}
