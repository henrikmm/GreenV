package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.azure.core.util.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "azure-blob")
public class AzureBlobCaptureObjectStorageAdapter implements CaptureObjectStorage {

    private static final String SHA256_METADATA = "sha256";

    private final BlobContainerClient containerClient;

    public AzureBlobCaptureObjectStorageAdapter(BlobContainerClient containerClient) {
        this.containerClient = containerClient;
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
            BlobClient blob = containerClient.getBlobClient(key);
            BlobUploadFromFileOptions options = new BlobUploadFromFileOptions(upload.path().toString())
                    .setMetadata(Map.of(SHA256_METADATA, upload.sha256()))
                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
            try {
                blob.uploadFromFileWithResponse(options, null, Context.NONE);
            } catch (BlobStorageException exception) {
                if (exception.getStatusCode() == 409 || exception.getStatusCode() == 412) {
                    return resolveCollision(key, checksum);
                }
                throw exception;
            }
            return new StoredObject(key, upload.sha256(), upload.bytes());
        } catch (ApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
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
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            containerClient.getBlobClient(key).downloadStream(output);
            if (output.size() > maximumBytes) {
                throw new ApplicationException(
                        FailureKind.INTERNAL_ERROR,
                        "capture_object_too_large",
                        "capture object exceeds its read limit");
            }
            return output.toByteArray();
        } catch (ApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
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
        try {
            // Not closed here on purpose: the stream IS the answer, and the caller closes it.
            return new ObjectContent(key, object.bytes(), containerClient.getBlobClient(key).openInputStream());
        } catch (RuntimeException exception) {
            throw unavailable("could not read " + key, exception);
        }
    }

    private Optional<StoredObject> find(String objectKey) {
        try {
            BlobClient blob = containerClient.getBlobClient(objectKey);
            if (!blob.exists()) {
                return Optional.empty();
            }
            var properties = blob.getProperties();
            return Optional.of(new StoredObject(
                    objectKey,
                    properties.getMetadata().get(SHA256_METADATA),
                    properties.getBlobSize()));
        } catch (BlobStorageException exception) {
            if (exception.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("could not inspect " + objectKey, exception);
        } catch (RuntimeException exception) {
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
