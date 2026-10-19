package br.com.greenv.videoapi.domain;

/** What the phone is told once its video is safely stored. */
public record DemoUploadReceipt(String captureId, long bytes, String sha256, long transferMs) {
}
