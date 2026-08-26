package br.com.greenv.frameextractor.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.service.ExtractionService;
import br.com.greenv.frameextractor.service.SegmentExtractionHandler;
import br.com.greenv.frameextractor.service.SegmentExtractionService;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CloudAgnosticArchitectureTest {

    @Test
    void applicationServicesDoNotDependOnInfrastructureAdapters() {
        assertCloudNeutral(ExtractionService.class);
        assertCloudNeutral(SegmentExtractionService.class);
        assertCloudNeutral(SegmentExtractionHandler.class);
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
}
