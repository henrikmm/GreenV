package br.com.greenv.videoapi.api;

/** The id the phone uploads its video under, and where. */
public record DemoAnnounceResponse(String id, String uploadPath) {

    public static DemoAnnounceResponse of(String captureId) {
        return new DemoAnnounceResponse(captureId, "/v1/demo/captures/" + captureId + "/video");
    }
}
