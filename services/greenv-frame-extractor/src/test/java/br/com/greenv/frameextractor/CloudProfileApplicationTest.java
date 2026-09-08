package br.com.greenv.frameextractor;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.frameextractor.api.ExtractionController;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import br.com.greenv.frameextractor.port.SegmentObjectStorage;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The deployed worker selects the S3 and Azure Queue adapters, which removes the local pipeline
 * store. Before this test the legacy beans still asked for that store and the container failed to
 * start with "No qualifying bean of type LegacyPipelineStore", so every uploaded segment stayed
 * queued. Local polling is deliberately left at its default here: the misconfiguration this
 * guards against is a cloud deployment that forgets to disable it.
 */
@SpringBootTest(
        properties = {
            "greenv.adapters.object-storage=s3",
            "greenv.adapters.segment-queue=azure-queue",
            "greenv.storage.s3.bucket=greenv-captures",
            "greenv.storage.s3.region=auto",
            "greenv.storage.s3.endpoint=https://account.r2.cloudflarestorage.com",
            "greenv.storage.s3.access-key=access-key",
            "greenv.storage.s3.secret-key=secret-key",
            "greenv.queue.azure-queue.queue=greenv-segment-extract-v2",
            "greenv.queue.azure-queue.connection-string=UseDevelopmentStorage=true",
            "management.health.rabbit.enabled=false"
        })
class CloudProfileApplicationTest {

    private static final Path TEST_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"),
            "greenv-frame-extractor-cloud-context-" + UUID.randomUUID());

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("greenv.extractor.root", TEST_ROOT::toString);
    }

    @Test
    void startsTheSegmentPathWithoutTheLegacyWholeVideoPipeline() {
        assertThat(context.getBeansOfType(SegmentExtractionUseCase.class)).isNotEmpty();
        assertThat(context.getBeansOfType(SegmentObjectStorage.class)).isNotEmpty();
        assertThat(context.getBeansOfType(ExtractionController.class)).isEmpty();
    }
}
