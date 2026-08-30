package br.com.greenv.frameextractor.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.frameextractor.api.ExtractionController;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.port.LegacyExtractionUseCase;
import br.com.greenv.frameextractor.port.LegacyPipelineStore;
import br.com.greenv.frameextractor.port.LegacyTaskInbox;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import br.com.greenv.frameextractor.port.SegmentObjectStorage;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.port.SegmentProcessor;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import br.com.greenv.frameextractor.service.ExtractionService;
import br.com.greenv.frameextractor.service.LegacyExtractionHandler;
import br.com.greenv.frameextractor.service.SegmentExtractionHandler;
import br.com.greenv.frameextractor.service.SegmentExtractionService;
import br.com.greenv.frameextractor.storage.JdbcCaptureSegmentStoreAdapter;
import br.com.greenv.frameextractor.storage.LocalLegacyPipelineStoreAdapter;
import br.com.greenv.frameextractor.storage.LocalSegmentObjectStorageAdapter;
import br.com.greenv.frameextractor.storage.S3SegmentObjectStorageAdapter;
import br.com.greenv.frameextractor.storage.AzureBlobSegmentObjectStorageAdapter;
import br.com.greenv.frameextractor.task.AzureQueueSegmentWorkQueueAdapter;
import br.com.greenv.frameextractor.task.AzureServiceBusSegmentWorkQueueAdapter;
import br.com.greenv.frameextractor.task.AzureQueueSegmentExtractionPollerAdapter;
import br.com.greenv.frameextractor.task.AzureServiceBusSegmentExtractionPollerAdapter;
import br.com.greenv.frameextractor.task.JacksonSegmentMessageCodecAdapter;
import br.com.greenv.frameextractor.task.LocalLegacyTaskInboxAdapter;
import br.com.greenv.frameextractor.task.LocalLegacyTaskPollerAdapter;
import br.com.greenv.frameextractor.task.RabbitMqSegmentExtractionListener;
import br.com.greenv.frameextractor.task.RabbitMqSegmentWorkQueueAdapter;
import br.com.greenv.frameextractor.task.SqsSegmentWorkQueueAdapter;
import br.com.greenv.frameextractor.task.SqsSegmentExtractionPollerAdapter;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class CloudAgnosticArchitectureTest {

    @Test
    void inboundAdaptersDependOnUseCaseInterfaces() {
        assertGreenVDependenciesAreInterfaces(ExtractionController.class);
        assertGreenVDependenciesAreInterfaces(RabbitMqSegmentExtractionListener.class);
        assertGreenVDependenciesAreInterfaces(LocalLegacyTaskPollerAdapter.class);
        assertThat(SegmentExtractionUseCase.class).isAssignableFrom(SegmentExtractionHandler.class);
        assertThat(LegacyExtractionUseCase.class).isAssignableFrom(LegacyExtractionHandler.class);
        assertThat(SegmentProcessor.class).isAssignableFrom(SegmentExtractionService.class);
    }

    @Test
    void outboundPortsDoNotDependOnApplicationServices() {
        assertNoMethodTypeFromPackage(CaptureSegmentStore.class, "br.com.greenv.frameextractor.service");
        assertNoMethodTypeFromPackage(LegacyPipelineStore.class, "br.com.greenv.frameextractor.service");
        assertNoMethodTypeFromPackage(LegacyTaskInbox.class, "br.com.greenv.frameextractor.service");
    }

    @Test
    void providerAdaptersHaveOnePortResponsibility() {
        assertThat(LocalSegmentObjectStorageAdapter.class.getInterfaces())
                .containsExactly(SegmentObjectStorage.class);
        assertThat(S3SegmentObjectStorageAdapter.class.getInterfaces())
                .containsExactly(SegmentObjectStorage.class);
        assertThat(AzureBlobSegmentObjectStorageAdapter.class.getInterfaces())
                .containsExactly(SegmentObjectStorage.class);
        assertThat(LocalLegacyPipelineStoreAdapter.class.getInterfaces())
                .containsExactly(LegacyPipelineStore.class);
        assertThat(LocalLegacyTaskInboxAdapter.class.getInterfaces())
                .containsExactly(LegacyTaskInbox.class);
        assertThat(CaptureSegmentStore.class).isAssignableFrom(JdbcCaptureSegmentStoreAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(RabbitMqSegmentWorkQueueAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(SqsSegmentWorkQueueAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(AzureQueueSegmentWorkQueueAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(AzureServiceBusSegmentWorkQueueAdapter.class);
        assertThat(SegmentMessageCodec.class).isAssignableFrom(JacksonSegmentMessageCodecAdapter.class);
    }

    @Test
    void applicationServicesDoNotDependOnInfrastructureAdapters() {
        assertCloudNeutral(ExtractionService.class);
        assertCloudNeutral(LegacyExtractionHandler.class);
        assertCloudNeutral(SegmentExtractionService.class);
        assertCloudNeutral(SegmentExtractionHandler.class);
    }

    @Test
    void cloudAdaptersAreSelectedOnlyByExplicitConfigurationValues() {
        assertAdapterValue(S3SegmentObjectStorageAdapter.class, "s3");
        assertAdapterValue(AzureBlobSegmentObjectStorageAdapter.class, "azure-blob");
        assertAdapterValue(SqsSegmentWorkQueueAdapter.class, "sqs");
        assertAdapterValue(SqsSegmentExtractionPollerAdapter.class, "sqs");
        assertAdapterValue(AzureQueueSegmentWorkQueueAdapter.class, "azure-queue");
        assertAdapterValue(AzureQueueSegmentExtractionPollerAdapter.class, "azure-queue");
        assertAdapterValue(AzureServiceBusSegmentWorkQueueAdapter.class, "azure-service-bus");
        assertAdapterValue(AzureServiceBusSegmentExtractionPollerAdapter.class, "azure-service-bus");
    }

    @Test
    void segmentContractUsesObjectKeysAndMatchesTheApiCopy() throws Exception {
        assertThat(Arrays.stream(SegmentExtractionRequest.class.getRecordComponents())
                        .map(RecordComponent::getName))
                .contains("videoObjectKey", "telemetryObjectKey", "outputPrefix")
                .noneMatch(name -> name.toLowerCase().endsWith("uri"));

        Path workerContract = Path.of("src/main/resources/contracts/segment-extraction-request-v2.schema.json");
        Path apiContract = Path.of("../greenv-video-api/src/main/resources/contracts/segment-extraction-request-v2.schema.json");
        assertThat(Files.readString(workerContract)).isEqualTo(Files.readString(apiContract));
        Path workerManifest = Path.of("src/main/resources/contracts/segment-manifest-v2.schema.json");
        Path apiManifest = Path.of("../greenv-video-api/src/main/resources/contracts/segment-manifest-v2.schema.json");
        assertThat(Files.readString(workerManifest)).isEqualTo(Files.readString(apiManifest));
    }

    private static void assertCloudNeutral(Class<?> type) {
        assertThat(type.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(packageName -> packageName.startsWith("br.com.greenv.frameextractor.storage")
                        || packageName.startsWith("br.com.greenv.frameextractor.task")
                        || packageName.startsWith("org.springframework.jdbc")
                        || packageName.startsWith("org.springframework.amqp"));
    }

    private static void assertGreenVDependenciesAreInterfaces(Class<?> adapter) {
        assertThat(Arrays.stream(adapter.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(field -> field.getType())
                        .filter(type -> type.getPackageName().startsWith("br.com.greenv")))
                .allMatch(Class::isInterface)
                .allMatch(type -> type.getPackageName().equals("br.com.greenv.frameextractor.port"));
    }

    private static void assertNoMethodTypeFromPackage(Class<?> port, String forbiddenPackage) {
        assertThat(Arrays.stream(port.getDeclaredMethods())
                        .flatMap(method -> java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(method.getReturnType()),
                                Arrays.stream(method.getParameterTypes())))
                        .map(Class::getPackageName))
                .noneMatch(packageName -> packageName.startsWith(forbiddenPackage));
    }

    private static void assertAdapterValue(Class<?> adapter, String expected) {
        assertThat(adapter.getAnnotation(ConditionalOnProperty.class).havingValue()).isEqualTo(expected);
    }
}
