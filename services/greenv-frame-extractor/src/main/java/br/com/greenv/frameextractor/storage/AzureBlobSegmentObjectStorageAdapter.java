package br.com.greenv.frameextractor.storage;

import br.com.greenv.frameextractor.port.SegmentObjectStorage;
import br.com.greenv.frameextractor.service.ExtractionException;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "azure-blob")
public class AzureBlobSegmentObjectStorageAdapter implements SegmentObjectStorage {

    private static final String SHA256_METADATA = "sha256";

    private final BlobContainerClient containerClient;
    private final ObjectMapper objectMapper;

    public AzureBlobSegmentObjectStorageAdapter(
            BlobContainerClient containerClient,
            ObjectMapper objectMapper) {
        this.containerClient = containerClient;
        this.objectMapper = objectMapper;
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
            containerClient.getBlobClient(key).downloadToFile(destination.toString(), true);
            return CloudStorageSupport.descriptor(key, destination);
        } catch (IOException | RuntimeException exception) {
            throw failed("object_download_failed", "could not materialize " + key, exception);
        }
    }

    @Override
    public ObjectDescriptor putFile(String objectKey, Path source) {
        String key = CloudStorageSupport.requireObjectKey(objectKey);
        try {
            String checksum = CloudStorageSupport.sha256(source);
            BlobClient blob = containerClient.getBlobClient(key);
            blob.uploadFromFile(source.toString(), true);
            blob.setMetadata(Map.of(SHA256_METADATA, checksum));
            return new ObjectDescriptor(key, checksum, Files.size(source));
        } catch (IOException | RuntimeException exception) {
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
            return objectMapper.readValue(containerClient.getBlobClient(key).downloadContent().toBytes(), type);
        } catch (JacksonException exception) {
            throw new ExtractionException("metadata_read_failed", "could not read " + key, false, exception);
        } catch (RuntimeException exception) {
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
            containerClient.getBlobClient(key).deleteIfExists();
        } catch (RuntimeException exception) {
            throw failed("object_delete_failed", "could not delete " + key, exception);
        }
    }

    private Optional<ObjectDescriptor> find(String objectKey) {
        try {
            BlobClient blob = containerClient.getBlobClient(objectKey);
            if (!blob.exists()) {
                return Optional.empty();
            }
            var properties = blob.getProperties();
            return Optional.of(new ObjectDescriptor(
                    objectKey,
                    properties.getMetadata().get(SHA256_METADATA),
                    properties.getBlobSize()));
        } catch (BlobStorageException exception) {
            if (exception.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw failed("artifact_stat_failed", "could not inspect " + objectKey, exception);
        } catch (RuntimeException exception) {
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
