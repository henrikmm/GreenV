package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.config.S3StorageProperties;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "s3")
public class S3CaptureObjectStorageAdapter implements CaptureObjectStorage {

    private static final String SHA256_METADATA = "sha256";

    private final S3Client s3Client;
    private final String bucket;

    public S3CaptureObjectStorageAdapter(S3Client s3Client, S3StorageProperties properties) {
        this.s3Client = s3Client;
        this.bucket = properties.bucket();
    }

    @Override
    public StoredObject put(String objectKey, InputStream input, String expectedSha256, long maximumBytes) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        String checksum = CloudStorageSupport.requireSha256(expectedSha256);
        Optional<StoredObject> existing = find(key);
        if (existing.isPresent()) {
            if (!checksum.equals(existing.get().sha256())) {
                throw conflict(key);
            }
            return existing.get();
        }

        try (CloudStorageSupport.StagedUpload upload = CloudStorageSupport.stage(input, checksum, maximumBytes)) {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentLength(upload.bytes())
                    .metadata(Map.of(SHA256_METADATA, upload.sha256()))
                    .ifNoneMatch("*")
                    .build();
            try {
                s3Client.putObject(request, RequestBody.fromFile(upload.path()));
            } catch (S3Exception exception) {
                if (exception.statusCode() == 409 || exception.statusCode() == 412) {
                    return resolveCollision(key, checksum);
                }
                throw exception;
            }
            return new StoredObject(key, upload.sha256(), upload.bytes());
        } catch (ApplicationException exception) {
            throw exception;
        } catch (SdkException exception) {
            throw unavailable("could not upload " + key, exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return find(CloudStorageSupport.requireObjectKey(objectKey)).isPresent();
    }

    @Override
    public byte[] read(String objectKey, long maximumBytes) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        StoredObject object = find(key).orElseThrow(() -> new ApplicationException(
                FailureKind.NOT_FOUND, "capture_object_not_found", "capture object does not exist"));
        if (object.bytes() > maximumBytes) {
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR,
                    "capture_object_too_large",
                    "capture object exceeds its read limit");
        }
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try (ResponseInputStream<GetObjectResponse> input = s3Client.getObject(request)) {
            return CloudStorageSupport.readBounded(input, maximumBytes);
        } catch (ApplicationException exception) {
            throw exception;
        } catch (IOException | SdkException exception) {
            throw unavailable("could not read " + key, exception);
        }
    }

    @Override
    public ObjectContent open(String objectKey, long maximumBytes) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        StoredObject object = find(key).orElseThrow(() -> new ApplicationException(
                FailureKind.NOT_FOUND, "capture_object_not_found", "capture object does not exist"));
        if (object.bytes() > maximumBytes) {
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR,
                    "capture_object_too_large",
                    "capture object exceeds its read limit");
        }
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try {
            // Not closed here on purpose: the stream IS the answer, and the caller closes it.
            ResponseInputStream<GetObjectResponse> input = s3Client.getObject(request);
            return new ObjectContent(key, object.bytes(), input);
        } catch (SdkException exception) {
            throw unavailable("could not read " + key, exception);
        }
    }

    private Optional<StoredObject> find(String objectKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            String checksum = response.metadata().get(SHA256_METADATA);
            return Optional.of(new StoredObject(objectKey, checksum, response.contentLength()));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("could not inspect " + objectKey, exception);
        } catch (SdkException exception) {
            throw unavailable("could not inspect " + objectKey, exception);
        }
    }

    private StoredObject resolveCollision(String key, String expectedSha256) {
        StoredObject existing = find(key).orElseThrow(() -> unavailable(
                "object creation conflicted but no object is visible for " + key,
                new IllegalStateException("conditional write failed")));
        if (!expectedSha256.equals(existing.sha256())) {
            throw conflict(key);
        }
        return existing;
    }

    private static ApplicationException conflict(String key) {
        return new ApplicationException(
                FailureKind.CONFLICT,
                "segment_object_conflict",
                key + " already exists with a different checksum");
    }

    private static ApplicationException unavailable(String message, Exception exception) {
        return new ApplicationException(
                FailureKind.DEPENDENCY_UNAVAILABLE,
                "capture_storage_unavailable",
                message + ": " + exception.getMessage());
    }
}
