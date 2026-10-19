package br.com.greenv.videoapi.api;

import java.util.List;

/** {@code {captures: [...]}}, newest first — the body the studio's own phone route answers with. */
public record DemoCaptureListResponse(List<DemoPhoneCaptureResponse> captures) {
}
