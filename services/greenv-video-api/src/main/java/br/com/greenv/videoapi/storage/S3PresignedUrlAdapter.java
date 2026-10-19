package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.config.S3StorageProperties;
import br.com.greenv.videoapi.port.ObjectUrlSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Presigned GETs straight to the bucket, so a 50 MB reconstruction never passes through the API.
 *
 * <p>A link is reused until half its life is gone. The site asks for the same run every two
 * seconds, and a URL that changed on every answer would look like a new file each time — to the
 * browser's cache and to any page that compares links to decide whether to download again. Two
 * replicas still hand out two different links for the same object; the path before the query is
 * what identifies it.
 */
@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "s3")
public class S3PresignedUrlAdapter implements ObjectUrlSigner {

    /** Well above what one demo touches: a video, 112 frames and a few artifacts per capture. */
    private static final int MAX_CACHED = 10_000;

    private final S3Presigner presigner;
    private final String bucket;
    private final Clock clock;
    private final Map<String, Signed> cache = new ConcurrentHashMap<>();

    public S3PresignedUrlAdapter(S3Presigner presigner, S3StorageProperties properties, Clock clock) {
        this.presigner = presigner;
        this.bucket = properties.bucket();
        this.clock = clock;
    }

    @Override
    public String sign(String objectKey, Duration ttl) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        Instant now = clock.instant();
        Signed cached = cache.get(key);
        if (cached != null && cached.ttl().equals(ttl) && now.isBefore(cached.reuseUntil())) {
            return cached.url();
        }
        String url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                        .build())
                .url()
                .toString();
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, new Signed(url, ttl, now.plus(ttl.dividedBy(2))));
        return url;
    }

    private record Signed(String url, Duration ttl, Instant reuseUntil) {
    }
}
