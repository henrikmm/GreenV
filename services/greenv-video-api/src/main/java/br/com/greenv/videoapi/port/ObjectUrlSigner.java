package br.com.greenv.videoapi.port;

import java.time.Duration;

/**
 * An absolute URL a browser can GET an object from, without credentials of its own.
 *
 * <p>For S3-compatible storage this is a presigned GET straight to the bucket, so the bytes never
 * pass through the API. For local storage it is a route on this API that streams the object,
 * which is what lets the whole demo run on one laptop.
 */
public interface ObjectUrlSigner {

    String sign(String objectKey, Duration ttl);
}
