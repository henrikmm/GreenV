package br.com.greenv.videoapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoPhase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retention and delete remove only what a capture owns. The recorded examples under
 * {@code demo/examples/} are no capture's, have no row, and must survive both whatever a
 * capture's manifest claims.
 */
class DemoCleanupServiceTest {

    private static final String ID = "cap-20261024-143000-a1b2";
    private static final Instant START = Instant.parse("2026-10-24T14:30:00Z");

    @Test
    void neverNamesARecordedExampleAmongACapturesObjects() {
        var json = JsonMapper.builder().build();
        DemoCapture capture = DemoCapture.announced(ID, json.readTree("{\"schema\":\"verge-phone-capture/1\"}"), START, START)
                .append(DemoPhase.of("fetched", START, START, null), START)
                .withFrameKeys(List.of(
                        "demo/" + ID + "/sampled-frames/frame-0001.jpg",
                        "demo/examples/03-celular-gabinete-pro6000/frames/frame-0001.jpg"))
                .withResult(json.readTree("""
                        {"artifacts":[
                          {"object_key":"demo/%s/depth/run-1/scene.glb"},
                          {"object_key":"demo/examples/03-celular-gabinete-pro6000/scene.glb"},
                          {"object_key":"demo/examples/index.json"},
                          {"object_key":"demo/%s/../examples/index.json"}
                        ]}
                        """.formatted(ID, ID)), "mock", 1.0);

        assertThat(DemoCleanupService.objectKeys(capture)).containsExactlyInAnyOrder(
                "demo/" + ID + "/sampled-frames/frame-0001.jpg",
                "demo/" + ID + "/depth/run-1/scene.glb");
    }
}
