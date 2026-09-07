package br.com.greenv.videoapi.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.greenv.videoapi.config.S3StorageProperties;
import br.com.greenv.videoapi.service.ApplicationException;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class CloudCaptureObjectStorageAdaptersTest {

    private static final String KEY = "capture-sessions/session/segments/00000000/source.mp4";

    @Test
    void s3UploadsWithPortableChecksumMetadata() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());
        byte[] content = "capture".getBytes(StandardCharsets.UTF_8);
        var adapter = new S3CaptureObjectStorageAdapter(client, s3Properties());

        var stored = adapter.put(KEY, new ByteArrayInputStream(content), sha256(content), 1024);

        ArgumentCaptor<PutObjectRequest> uploaded = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(uploaded.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(uploaded.getValue().bucket()).isEqualTo("captures");
        assertThat(uploaded.getValue().metadata()).containsEntry("sha256", sha256(content));
        assertThat(stored.bytes()).isEqualTo(content.length);
    }

    @Test
    void s3RejectsAnIdempotencyCollision() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(10L)
                .metadata(Map.of("sha256", "b".repeat(64)))
                .build());
        var adapter = new S3CaptureObjectStorageAdapter(client, s3Properties());

        assertThatThrownBy(() -> adapter.put(
                        KEY,
                        new ByteArrayInputStream(new byte[] {1}),
                        "a".repeat(64),
                        1024))
                .isInstanceOf(ApplicationException.class)
                .extracting(exception -> ((ApplicationException) exception).code())
                .isEqualTo("segment_object_conflict");
    }

    @Test
    void s3TreatsAConcurrentSameChecksumWriteAsAnIdempotentSuccess() throws Exception {
        S3Client client = mock(S3Client.class);
        byte[] content = "capture".getBytes(StandardCharsets.UTF_8);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build())
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength((long) content.length)
                        .metadata(Map.of("sha256", sha256(content)))
                        .build());
        when(client.putObject(
                        any(PutObjectRequest.class),
                        any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("precondition").build());
        var adapter = new S3CaptureObjectStorageAdapter(client, s3Properties());

        var stored = adapter.put(KEY, new ByteArrayInputStream(content), sha256(content), 1024);

        assertThat(stored.sha256()).isEqualTo(sha256(content));
        assertThat(stored.bytes()).isEqualTo(content.length);
    }

    @Test
    void azureBlobUploadsWithPortableChecksumMetadata() throws Exception {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blob = mock(BlobClient.class);
        when(container.getBlobClient(KEY)).thenReturn(blob);
        when(blob.exists()).thenReturn(false);
        byte[] content = "capture".getBytes(StandardCharsets.UTF_8);
        var adapter = new AzureBlobCaptureObjectStorageAdapter(container);

        var stored = adapter.put(KEY, new ByteArrayInputStream(content), sha256(content), 1024);

        ArgumentCaptor<BlobUploadFromFileOptions> uploaded = ArgumentCaptor.forClass(BlobUploadFromFileOptions.class);
        verify(blob).uploadFromFileWithResponse(
                uploaded.capture(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(com.azure.core.util.Context.NONE));
        assertThat(uploaded.getValue().getMetadata()).containsEntry("sha256", sha256(content));
        assertThat(uploaded.getValue().getRequestConditions().getIfNoneMatch()).isEqualTo("*");
        assertThat(stored.bytes()).isEqualTo(content.length);
    }

    @Test
    void azureBlobTreatsAConcurrentSameChecksumWriteAsAnIdempotentSuccess() throws Exception {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blob = mock(BlobClient.class);
        BlobProperties blobProperties = mock(BlobProperties.class);
        BlobStorageException precondition = mock(BlobStorageException.class);
        byte[] content = "capture".getBytes(StandardCharsets.UTF_8);
        when(container.getBlobClient(KEY)).thenReturn(blob);
        when(blob.exists()).thenReturn(false, true);
        when(blob.getProperties()).thenReturn(blobProperties);
        when(blobProperties.getMetadata()).thenReturn(Map.of("sha256", sha256(content)));
        when(blobProperties.getBlobSize()).thenReturn((long) content.length);
        when(precondition.getStatusCode()).thenReturn(412);
        when(blob.uploadFromFileWithResponse(
                        any(BlobUploadFromFileOptions.class),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(com.azure.core.util.Context.NONE)))
                .thenThrow(precondition);
        var adapter = new AzureBlobCaptureObjectStorageAdapter(container);

        var stored = adapter.put(KEY, new ByteArrayInputStream(content), sha256(content), 1024);

        assertThat(stored.sha256()).isEqualTo(sha256(content));
        assertThat(stored.bytes()).isEqualTo(content.length);
    }

    @Test
    void cloudAdaptersRejectProviderUrisAndTraversal() {
        S3CaptureObjectStorageAdapter adapter = new S3CaptureObjectStorageAdapter(mock(S3Client.class), s3Properties());

        assertThatThrownBy(() -> adapter.exists("s3://captures/source.mp4"))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> adapter.exists("../outside/source.mp4"))
                .isInstanceOf(ApplicationException.class);
    }

    private static S3StorageProperties s3Properties() {
        return new S3StorageProperties("captures", "us-east-1", "", false, "", "");
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
