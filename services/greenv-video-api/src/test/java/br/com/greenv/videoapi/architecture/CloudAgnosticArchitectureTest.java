package br.com.greenv.videoapi.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.api.CaptureSessionController;
import br.com.greenv.videoapi.api.JobController;
import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.LegacyJobUseCase;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import br.com.greenv.videoapi.service.CaptureSessionService;
import br.com.greenv.videoapi.service.JobService;
import br.com.greenv.videoapi.service.TransientCleanupService;
import br.com.greenv.videoapi.storage.JdbcCaptureSessionStoreAdapter;
import br.com.greenv.videoapi.storage.LocalCaptureObjectStorageAdapter;
import br.com.greenv.videoapi.task.RabbitMqSegmentWorkQueueAdapter;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CloudAgnosticArchitectureTest {

    @Test
    void inboundAdaptersDependOnUseCaseInterfaces() {
        assertInboundDependencies(CaptureSessionController.class);
        assertInboundDependencies(JobController.class);
        assertThat(CaptureSessionUseCase.class).isAssignableFrom(CaptureSessionService.class);
        assertThat(LegacyJobUseCase.class).isAssignableFrom(JobService.class);
    }

    @Test
    void inboundPortsDoNotExposeHttpAdapterTypes() {
        assertNoMethodTypeFromPackage(CaptureSessionUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(LegacyJobUseCase.class, "br.com.greenv.videoapi.api");
    }

    @Test
    void providerAdaptersImplementOutboundPorts() {
        assertThat(CaptureSessionStore.class).isAssignableFrom(JdbcCaptureSessionStoreAdapter.class);
        assertThat(CaptureObjectStorage.class).isAssignableFrom(LocalCaptureObjectStorageAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(RabbitMqSegmentWorkQueueAdapter.class);
        assertThat(JdbcCaptureSessionStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(LocalCaptureObjectStorageAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(RabbitMqSegmentWorkQueueAdapter.class.getSimpleName()).endsWith("Adapter");
    }

    @Test
    void applicationServicesDoNotDependOnInfrastructureAdapters() {
        assertThat(CaptureSessionService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(JobService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(TransientCleanupService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
    }

    @Test
    void segmentContractUsesObjectKeysInsteadOfProviderUris() {
        assertThat(Arrays.stream(SegmentExtractionRequest.class.getRecordComponents())
                        .map(RecordComponent::getName))
                .contains("videoObjectKey", "telemetryObjectKey", "outputPrefix")
                .noneMatch(name -> name.toLowerCase().endsWith("uri"));

        UUID sessionId = UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f");
        assertThat(CaptureObjectKeys.video(sessionId, 7))
                .isEqualTo("capture-sessions/2d995d67-dd6f-4792-af22-480c43b37f2f/segments/00000007/source.mp4")
                .doesNotContain("://");
    }

    private boolean isAdapterPackage(String packageName) {
        return packageName.startsWith("br.com.greenv.videoapi.storage")
                || packageName.startsWith("br.com.greenv.videoapi.task")
                || packageName.startsWith("org.springframework.jdbc")
                || packageName.startsWith("org.springframework.amqp");
    }

    private static void assertInboundDependencies(Class<?> adapter) {
        assertThat(Arrays.stream(adapter.getDeclaredFields())
                        .filter(field -> !Modifier.isStatic(field.getModifiers()))
                        .map(field -> field.getType()))
                .allMatch(Class::isInterface)
                .allMatch(type -> type.getPackageName().equals("br.com.greenv.videoapi.port"));
    }

    private static void assertNoMethodTypeFromPackage(Class<?> port, String forbiddenPackage) {
        assertThat(Arrays.stream(port.getDeclaredMethods())
                        .flatMap(method -> java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(method.getReturnType()),
                                Arrays.stream(method.getParameterTypes())))
                        .map(Class::getPackageName))
                .noneMatch(packageName -> packageName.startsWith(forbiddenPackage));
    }
}
