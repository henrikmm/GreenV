package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.config.S3StorageProperties;
import br.com.greenv.frameextractor.port.SegmentObjectStorage;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "s3")
public class S3SegmentObjectStorageAdapter implements SegmentObjectStorage {

    private static final String SHA256_METADATA = "sha256";

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String bucket;

    public S3SegmentObjectStorageAdapter(
            S3Client s3Client,
            ObjectMapper objectMapper,
            S3StorageProperties properties) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.bucket = properties.bucket();
    }

    @Override
    public boolean exists(String objectKey) {
        return find(CloudStorageSupport.requireObjectKey(objectKey)).isPresent();
    }

    @Override
    public ObjectDescriptor download(String objectKey, Path destination) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        if (find(key).isEmpty()) {
            throw missing(key);
        }
        try {
            Path parent = destination.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(destination);
            s3Client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build(),
                    destination);
            return CloudStorageSupport.descriptor(key, destination);
        } catch (IOException | SdkException exception) {
            throw failed("object_download_failed", "could not materialize " + key, exception);
        }
    }

    @Override
    public ObjectDescriptor putFile(String objectKey, Path source) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        try {
            String checksum = CloudStorageSupport.sha256(source);
            long bytes = Files.size(source);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentLength(bytes)
                            .metadata(Map.of(SHA256_METADATA, checksum))
                            .build(),
                    RequestBody.fromFile(source));
            return new ObjectDescriptor(key, checksum, bytes);
        } catch (IOException | SdkException exception) {
            throw failed("object_publish_failed", "could not publish " + key, exception);
        }
    }

    @Override
    public ObjectDescriptor putJson(String objectKey, Object value) {
        Path temporary = null;
        try {
            temporary = CloudStorageSupport.temporaryJsonFile();
            objectMapper.writeValue(temporary.toFile(), value);
            return putFile(objectKey, temporary);
        } catch (JacksonException | IOException exception) {
            throw failed("object_publish_failed", "could not publish " + objectKey, exception);
        } finally {
            CloudStorageSupport.deleteQuietly(temporary);
        }
    }

    @Override
    public <T> T readJson(String objectKey, Class<T> type) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        try {
            byte[] content = s3Client.getObjectAsBytes(
                            GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
            return objectMapper.readValue(content, type);
        } catch (JacksonException exception) {
            throw new ExtractionException("metadata_read_failed", "could not read " + key, false, exception);
        } catch (SdkException exception) {
            throw failed("metadata_read_failed", "could not read " + key, exception);
        }
    }

    @Override
    public ObjectDescriptor stat(String objectKey) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        return find(key).orElseThrow(() -> missing(key));
    }

    @Override
    public void delete(String objectKey) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException exception) {
            throw failed("object_delete_failed", "could not delete " + key, exception);
        }
    }

    private Optional<ObjectDescriptor> find(String objectKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return Optional.of(new ObjectDescriptor(
                    objectKey,
                    response.metadata().get(SHA256_METADATA),
                    response.contentLength()));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw failed("artifact_stat_failed", "could not inspect " + objectKey, exception);
        } catch (SdkException exception) {
            throw failed("artifact_stat_failed", "could not inspect " + objectKey, exception);
        }
    }

    private static ExtractionException missing(String key) {
        return new ExtractionException("object_missing", "object is not available: " + key, true);
    }

    private static ExtractionException failed(String code, String message, Exception exception) {
        return new ExtractionException(code, message, true, exception);
    }
}
