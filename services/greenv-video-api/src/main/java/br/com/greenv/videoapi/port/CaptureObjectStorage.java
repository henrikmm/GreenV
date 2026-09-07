package br.com.greenv.videoapi.port;

import java.io.InputStream;

/** Durable binary/object storage addressed only by provider-neutral object keys. */
public interface CaptureObjectStorage {

    StoredObject put(
            String objectKey,
            InputStream input,
            String expectedSha256,
            long maximumBytes);

    boolean exists(String objectKey);

    byte[] read(String objectKey, long maximumBytes);

    record StoredObject(String objectKey, String sha256, long bytes) {
    }
}
