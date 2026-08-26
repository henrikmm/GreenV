package br.com.greenv.videoapi.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.service.CaptureSessionService;
import br.com.greenv.videoapi.service.JobService;
import br.com.greenv.videoapi.service.TransientCleanupService;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CloudAgnosticArchitectureTest {

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
}
