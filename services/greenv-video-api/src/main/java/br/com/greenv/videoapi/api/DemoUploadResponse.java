package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.DemoUploadReceipt;

/** What arrived: the byte count and digest measured here, and how long the transfer took. */
public record DemoUploadResponse(String id, long bytes, String sha256, long transferMs) {

    public static DemoUploadResponse from(DemoUploadReceipt receipt) {
        return new DemoUploadResponse(receipt.captureId(), receipt.bytes(), receipt.sha256(), receipt.transferMs());
    }
}
