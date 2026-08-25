package br.com.greenv.frameextractor.api;

import br.com.greenv.frameextractor.domain.FrameManifest;

public record ExtractionResponse(
        String state,
        FrameManifest manifest,
        String errorCode,
        String errorMessage) {

    static ExtractionResponse ready(FrameManifest manifest) {
        return new ExtractionResponse("frames_ready", manifest, null, null);
    }

    static ExtractionResponse failed(String state, String code, String message) {
        return new ExtractionResponse(state, null, code, message);
    }
}
