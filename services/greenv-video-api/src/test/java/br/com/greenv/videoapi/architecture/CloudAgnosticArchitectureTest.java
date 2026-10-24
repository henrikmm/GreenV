package br.com.greenv.videoapi.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.api.AuthController;
import br.com.greenv.videoapi.api.CaptureSessionController;
import br.com.greenv.videoapi.api.DemoCaptureController;
import br.com.greenv.videoapi.api.DemoExampleController;
import br.com.greenv.videoapi.api.DemoGpuController;
import br.com.greenv.videoapi.api.DemoObjectController;
import br.com.greenv.videoapi.api.MeasurementController;
import br.com.greenv.videoapi.api.ServiceOrderController;
import br.com.greenv.videoapi.api.TeamController;
import br.com.greenv.videoapi.api.IdentityAdminController;
import br.com.greenv.videoapi.api.JwkSetController;
import br.com.greenv.videoapi.api.OAuthTokenController;
import br.com.greenv.videoapi.api.JobController;
import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.identifier.MonotonicUuidV7IdentifierAdapter;
import br.com.greenv.videoapi.port.AccessTokenIssuer;
import br.com.greenv.videoapi.port.AccessTokenVerifier;
import br.com.greenv.videoapi.port.AuthSessionStore;
import br.com.greenv.videoapi.port.AuthenticationUseCase;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.DemoCaptureStore;
import br.com.greenv.videoapi.port.DemoCaptureUseCase;
import br.com.greenv.videoapi.port.DemoExampleUseCase;
import br.com.greenv.videoapi.port.DemoGpuControl;
import br.com.greenv.videoapi.port.DemoGpuUseCase;
import br.com.greenv.videoapi.port.DemoFrameSampler;
import br.com.greenv.videoapi.port.DepthJobRunner;
import br.com.greenv.videoapi.port.ObjectUrlSigner;
import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.port.IdentityAdminUseCase;
import br.com.greenv.videoapi.port.JwkSetProvider;
import br.com.greenv.videoapi.port.MeasurementAnnouncementReader;
import br.com.greenv.videoapi.port.OAuthClientStore;
import br.com.greenv.videoapi.port.FrameReadingsReader;
import br.com.greenv.videoapi.port.OperationsUseCase;
import br.com.greenv.videoapi.port.PlaceNameResolver;
import br.com.greenv.videoapi.port.ServiceOrderStore;
import br.com.greenv.videoapi.port.TeamStore;
import br.com.greenv.videoapi.port.SecretHasher;
import br.com.greenv.videoapi.port.SessionCookieWriter;
import br.com.greenv.videoapi.port.UserStore;
import br.com.greenv.videoapi.port.LegacyJobUseCase;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import br.com.greenv.videoapi.port.SegmentMessageSerializer;
import br.com.greenv.videoapi.security.ResponseSessionCookieWriterAdapter;
import br.com.greenv.videoapi.service.AuthenticationService;
import br.com.greenv.videoapi.service.CaptureSessionService;
import br.com.greenv.videoapi.service.DemoCaptureService;
import br.com.greenv.videoapi.service.DemoCleanupService;
import br.com.greenv.videoapi.service.DemoExampleService;
import br.com.greenv.videoapi.service.DemoGpuService;
import br.com.greenv.videoapi.service.DemoPipelineService;
import br.com.greenv.videoapi.service.IdentityAdminService;
import br.com.greenv.videoapi.service.JobService;
import br.com.greenv.videoapi.service.FrameReadingsBackfillService;
import br.com.greenv.videoapi.service.OperationsService;
import br.com.greenv.videoapi.service.PlaceResolutionService;
import br.com.greenv.videoapi.service.TransientCleanupService;
import br.com.greenv.videoapi.storage.JdbcAuthSessionStoreAdapter;
import br.com.greenv.videoapi.storage.JdbcCaptureSessionStoreAdapter;
import br.com.greenv.videoapi.storage.JdbcDemoCaptureStoreAdapter;
import br.com.greenv.videoapi.storage.LocalDemoObjectUrlAdapter;
import br.com.greenv.videoapi.storage.S3PresignedUrlAdapter;
import br.com.greenv.videoapi.storage.JdbcOAuthClientStoreAdapter;
import br.com.greenv.videoapi.storage.JdbcServiceOrderStoreAdapter;
import br.com.greenv.videoapi.storage.JdbcTeamStoreAdapter;
import br.com.greenv.videoapi.storage.JdbcUserStoreAdapter;
import br.com.greenv.videoapi.storage.LocalCaptureObjectStorageAdapter;
import br.com.greenv.videoapi.storage.S3CaptureObjectStorageAdapter;
import br.com.greenv.videoapi.storage.AzureBlobCaptureObjectStorageAdapter;
import br.com.greenv.videoapi.task.AzureQueueMeasurementResultAdapter;
import br.com.greenv.videoapi.task.AzureQueueSegmentWorkQueueAdapter;
import br.com.greenv.videoapi.task.FfmpegDemoFrameSamplerAdapter;
import br.com.greenv.videoapi.task.MockDemoGpuControlAdapter;
import br.com.greenv.videoapi.task.MockDepthJobAdapter;
import br.com.greenv.videoapi.task.RunPodDemoGpuControlAdapter;
import br.com.greenv.videoapi.task.RunPodDepthJobAdapter;
import br.com.greenv.videoapi.token.BCryptSecretHasherAdapter;
import br.com.greenv.videoapi.token.NimbusAccessTokenIssuerAdapter;
import br.com.greenv.videoapi.token.NimbusAccessTokenVerifierAdapter;
import br.com.greenv.videoapi.token.RsaJwkSetProviderAdapter;
import br.com.greenv.videoapi.task.AzureServiceBusSegmentWorkQueueAdapter;
import br.com.greenv.videoapi.task.JacksonFrameReadingsAdapter;
import br.com.greenv.videoapi.task.JacksonMeasurementAnnouncementReaderAdapter;
import br.com.greenv.videoapi.task.NominatimPlaceNameAdapter;
import br.com.greenv.videoapi.task.JacksonSegmentMessageSerializerAdapter;
import br.com.greenv.videoapi.task.RabbitMqSegmentWorkQueueAdapter;
import br.com.greenv.videoapi.task.SqsSegmentWorkQueueAdapter;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class CloudAgnosticArchitectureTest {

    @Test
    void inboundAdaptersDependOnUseCaseInterfaces() {
        assertInboundDependencies(CaptureSessionController.class);
        assertInboundDependencies(MeasurementController.class);
        assertInboundDependencies(JobController.class);
        assertInboundDependencies(AuthController.class);
        assertInboundDependencies(OAuthTokenController.class);
        assertInboundDependencies(JwkSetController.class);
        assertInboundDependencies(IdentityAdminController.class);
        assertInboundDependencies(ServiceOrderController.class);
        assertInboundDependencies(TeamController.class);
        assertInboundDependencies(DemoCaptureController.class);
        assertInboundDependencies(DemoObjectController.class);
        assertInboundDependencies(DemoExampleController.class);
        assertInboundDependencies(DemoGpuController.class);
        assertThat(CaptureSessionUseCase.class).isAssignableFrom(CaptureSessionService.class);
        assertThat(LegacyJobUseCase.class).isAssignableFrom(JobService.class);
        assertThat(AuthenticationUseCase.class).isAssignableFrom(AuthenticationService.class);
        assertThat(IdentityAdminUseCase.class).isAssignableFrom(IdentityAdminService.class);
        assertThat(OperationsUseCase.class).isAssignableFrom(OperationsService.class);
        assertThat(DemoCaptureUseCase.class).isAssignableFrom(DemoCaptureService.class);
        assertThat(DemoExampleUseCase.class).isAssignableFrom(DemoExampleService.class);
        assertThat(DemoGpuUseCase.class).isAssignableFrom(DemoGpuService.class);
    }

    @Test
    void inboundPortsDoNotExposeHttpAdapterTypes() {
        assertNoMethodTypeFromPackage(CaptureSessionUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(LegacyJobUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(AuthenticationUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(IdentityAdminUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(OperationsUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(DemoCaptureUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(DemoExampleUseCase.class, "br.com.greenv.videoapi.api");
        assertNoMethodTypeFromPackage(DemoGpuUseCase.class, "br.com.greenv.videoapi.api");
    }

    @Test
    void providerAdaptersImplementOutboundPorts() {
        assertThat(CaptureSessionStore.class).isAssignableFrom(JdbcCaptureSessionStoreAdapter.class);
        assertThat(CaptureObjectStorage.class).isAssignableFrom(LocalCaptureObjectStorageAdapter.class);
        assertThat(CaptureObjectStorage.class).isAssignableFrom(S3CaptureObjectStorageAdapter.class);
        assertThat(CaptureObjectStorage.class).isAssignableFrom(AzureBlobCaptureObjectStorageAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(RabbitMqSegmentWorkQueueAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(SqsSegmentWorkQueueAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(AzureQueueSegmentWorkQueueAdapter.class);
        assertThat(SegmentWorkQueue.class).isAssignableFrom(AzureServiceBusSegmentWorkQueueAdapter.class);
        assertThat(SegmentMessageSerializer.class)
                .isAssignableFrom(JacksonSegmentMessageSerializerAdapter.class);
        assertThat(MeasurementAnnouncementReader.class)
                .isAssignableFrom(JacksonMeasurementAnnouncementReaderAdapter.class);
        assertThat(IdentifierGenerator.class).isAssignableFrom(MonotonicUuidV7IdentifierAdapter.class);
        assertThat(UserStore.class).isAssignableFrom(JdbcUserStoreAdapter.class);
        assertThat(AuthSessionStore.class).isAssignableFrom(JdbcAuthSessionStoreAdapter.class);
        assertThat(OAuthClientStore.class).isAssignableFrom(JdbcOAuthClientStoreAdapter.class);
        assertThat(TeamStore.class).isAssignableFrom(JdbcTeamStoreAdapter.class);
        assertThat(ServiceOrderStore.class).isAssignableFrom(JdbcServiceOrderStoreAdapter.class);
        assertThat(PlaceNameResolver.class).isAssignableFrom(NominatimPlaceNameAdapter.class);
        assertThat(DemoCaptureStore.class).isAssignableFrom(JdbcDemoCaptureStoreAdapter.class);
        assertThat(DemoFrameSampler.class).isAssignableFrom(FfmpegDemoFrameSamplerAdapter.class);
        assertThat(DepthJobRunner.class).isAssignableFrom(RunPodDepthJobAdapter.class);
        assertThat(DepthJobRunner.class).isAssignableFrom(MockDepthJobAdapter.class);
        assertThat(DemoGpuControl.class).isAssignableFrom(RunPodDemoGpuControlAdapter.class);
        assertThat(DemoGpuControl.class).isAssignableFrom(MockDemoGpuControlAdapter.class);
        assertThat(ObjectUrlSigner.class).isAssignableFrom(S3PresignedUrlAdapter.class);
        assertThat(ObjectUrlSigner.class).isAssignableFrom(LocalDemoObjectUrlAdapter.class);
        assertThat(FrameReadingsReader.class).isAssignableFrom(JacksonFrameReadingsAdapter.class);
        assertThat(AccessTokenIssuer.class).isAssignableFrom(NimbusAccessTokenIssuerAdapter.class);
        assertThat(AccessTokenVerifier.class).isAssignableFrom(NimbusAccessTokenVerifierAdapter.class);
        assertThat(JwkSetProvider.class).isAssignableFrom(RsaJwkSetProviderAdapter.class);
        assertThat(SecretHasher.class).isAssignableFrom(BCryptSecretHasherAdapter.class);
        assertThat(SessionCookieWriter.class)
                .isAssignableFrom(ResponseSessionCookieWriterAdapter.class);
        assertThat(JdbcCaptureSessionStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(LocalCaptureObjectStorageAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(S3CaptureObjectStorageAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(AzureBlobCaptureObjectStorageAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(RabbitMqSegmentWorkQueueAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(SqsSegmentWorkQueueAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(AzureQueueSegmentWorkQueueAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(AzureServiceBusSegmentWorkQueueAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(MonotonicUuidV7IdentifierAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JdbcUserStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JdbcAuthSessionStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JdbcOAuthClientStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JdbcTeamStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JdbcServiceOrderStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(NominatimPlaceNameAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JacksonFrameReadingsAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(NimbusAccessTokenIssuerAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(NimbusAccessTokenVerifierAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(RsaJwkSetProviderAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(BCryptSecretHasherAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(ResponseSessionCookieWriterAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JacksonMeasurementAnnouncementReaderAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(AzureQueueMeasurementResultAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(JdbcDemoCaptureStoreAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(FfmpegDemoFrameSamplerAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(RunPodDepthJobAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(MockDepthJobAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(RunPodDemoGpuControlAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(MockDemoGpuControlAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(S3PresignedUrlAdapter.class.getSimpleName()).endsWith("Adapter");
        assertThat(LocalDemoObjectUrlAdapter.class.getSimpleName()).endsWith("Adapter");
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
        assertThat(AuthenticationService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(IdentityAdminService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(OperationsService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(PlaceResolutionService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(FrameReadingsBackfillService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(DemoCaptureService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(DemoPipelineService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(DemoCleanupService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(DemoExampleService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
        assertThat(DemoGpuService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .noneMatch(this::isAdapterPackage);
    }

    @Test
    void cloudAdaptersAreSelectedOnlyByExplicitConfigurationValues() {
        assertAdapterValue(S3CaptureObjectStorageAdapter.class, "s3");
        assertAdapterValue(AzureBlobCaptureObjectStorageAdapter.class, "azure-blob");
        assertAdapterValue(SqsSegmentWorkQueueAdapter.class, "sqs");
        assertAdapterValue(AzureQueueSegmentWorkQueueAdapter.class, "azure-queue");
        assertAdapterValue(AzureServiceBusSegmentWorkQueueAdapter.class, "azure-service-bus");
        assertAdapterValue(AzureQueueMeasurementResultAdapter.class, "azure-queue");
        assertAdapterValue(S3PresignedUrlAdapter.class, "s3");
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

    private static void assertAdapterValue(Class<?> adapter, String expected) {
        assertThat(adapter.getAnnotation(ConditionalOnProperty.class).havingValue()).isEqualTo(expected);
    }
}
