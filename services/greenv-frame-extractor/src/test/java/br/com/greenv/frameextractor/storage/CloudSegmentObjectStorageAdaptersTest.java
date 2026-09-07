package br.com.greenv.frameextractor.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.config.S3StorageProperties;
import br.com.greenv.frameextractor.service.ExtractionException;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.json.JsonMapper;

class CloudSegmentObjectStorageAdaptersTest {

    private static final String KEY = "capture-sessions/session/segments/00000000/frames/frame-000001.jpg";

    @TempDir
    Path temporaryDirectory;

    @Test
    void s3PublishesWorkerArtifactsWithSha256Metadata() throws Exception {
        S3Client client = mock(S3Client.class);
        Path artifact = temporaryDirectory.resolve("frame.jpg");
        Files.writeString(artifact, "frame", StandardCharsets.UTF_8);
        var adapter = new S3SegmentObjectStorageAdapter(
                client,
                JsonMapper.builder().findAndAddModules().build(),
                new S3StorageProperties("captures", "us-east-1", "", false, "", ""));

        var stored = adapter.putFile(KEY, artifact);

        ArgumentCaptor<PutObjectRequest> uploaded = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(uploaded.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(uploaded.getValue().metadata()).containsEntry("sha256", sha256("frame"));
        assertThat(stored.sha256()).isEqualTo(sha256("frame"));
    }

    @Test
    void azureBlobPublishesWorkerArtifactsWithSha256Metadata() throws Exception {
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blob = mock(BlobClient.class);
        when(container.getBlobClient(KEY)).thenReturn(blob);
        Path artifact = temporaryDirectory.resolve("frame.jpg");
        Files.writeString(artifact, "frame", StandardCharsets.UTF_8);
        var adapter = new AzureBlobSegmentObjectStorageAdapter(
                container,
                JsonMapper.builder().findAndAddModules().build());

        var stored = adapter.putFile(KEY, artifact);

        verify(blob).uploadFromFile(artifact.toString(), true);
        verify(blob).setMetadata(Map.of("sha256", sha256("frame")));
        assertThat(stored.bytes()).isEqualTo(5);
    }

    @Test
    void cloudStorageRejectsProviderUrisAndTraversal() {
        var adapter = new S3SegmentObjectStorageAdapter(
                mock(S3Client.class),
                JsonMapper.builder().build(),
                new S3StorageProperties("captures", "us-east-1", "", false, "", ""));

        assertThatThrownBy(() -> adapter.exists("s3://captures/frame.jpg"))
                .isInstanceOf(ExtractionException.class);
        assertThatThrownBy(() -> adapter.exists("../outside/frame.jpg"))
                .isInstanceOf(ExtractionException.class);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
