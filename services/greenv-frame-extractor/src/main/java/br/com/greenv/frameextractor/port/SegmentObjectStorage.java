package br.com.greenv.frameextractor.port;

import java.nio.file.Path;

/** Durable object storage addressed by opaque, provider-neutral keys. */
public interface SegmentObjectStorage {

    boolean exists(String objectKey);

    ObjectDescriptor download(String objectKey, Path destination);

    ObjectDescriptor putFile(String objectKey, Path source);

    ObjectDescriptor putJson(String objectKey, Object value);

    <T> T readJson(String objectKey, Class<T> type);

    ObjectDescriptor stat(String objectKey);

    void delete(String objectKey);

    record ObjectDescriptor(String objectKey, String sha256, long bytes) {
    }
}
