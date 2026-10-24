package br.com.greenv.videoapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoGpuState;
import br.com.greenv.videoapi.port.DemoGpuControl;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DemoGpuServiceTest {

    private static final String CODE = "pairing-code";

    @Test
    void answersBadGatewayWhenTheProviderCannotBeReached() {
        DemoGpuService service = new DemoGpuService(unreachable(new ArrayList<>()), JsonMapper.builder().build(), properties());

        assertThatThrownBy(service::gpu)
                .isInstanceOfSatisfying(ApplicationException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(FailureKind.UPSTREAM_FAILURE);
                    assertThat(failure.code()).isEqualTo("demo_gpu_unavailable");
                });
        assertThatThrownBy(() -> service.keepWarm("Bearer " + CODE, body("{\"keepWarm\":true}")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(FailureKind.UPSTREAM_FAILURE));
    }

    @Test
    void checksThePairingCodeBeforeTheBodyAndTheProvider() {
        List<Integer> written = new ArrayList<>();
        DemoGpuService service = new DemoGpuService(unreachable(written), JsonMapper.builder().build(), properties());

        assertThatThrownBy(() -> service.keepWarm(null, body("not json")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(FailureKind.UNAUTHORIZED));
        assertThatThrownBy(() -> service.keepWarm("Bearer nope-nope", body("not json")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(FailureKind.FORBIDDEN));
        assertThatThrownBy(() -> service.keepWarm("Bearer " + CODE, body("not json")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("keep_warm_required"));
        assertThat(written).isEmpty();
    }

    private static DemoGpuControl unreachable(List<Integer> written) {
        return new DemoGpuControl() {
            @Override
            public DemoGpuState state() {
                throw new IllegalStateException("GET https://rest.runpod.io/v1/endpoints/x failed: timed out");
            }

            @Override
            public void setWorkersMin(int workersMin) {
                written.add(workersMin);
                throw new IllegalStateException("PATCH https://rest.runpod.io/v1/endpoints/x failed: timed out");
            }
        };
    }

    private static ByteArrayInputStream body(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static DemoProperties properties() {
        return new DemoProperties(
                true, CODE, 1024, 112, 10, 1024, 504, "mock",
                Duration.ofMinutes(20), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(1),
                3, "http://127.0.0.1:8080", "ffmpeg", "ffprobe", null);
    }
}
