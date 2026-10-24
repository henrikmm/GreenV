package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoGpuState;
import br.com.greenv.videoapi.port.DemoGpuControl;
import br.com.greenv.videoapi.port.DemoGpuUseCase;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the demo's GPU warm, or lets it go cold.
 *
 * <p>Warm means {@code workersMin = 1}: a capture starts on a worker that is already up instead of
 * waiting minutes for one, and that worker bills every second it is kept. So the switch takes the
 * pairing code, and it moves {@code workersMin} between 0 and 1 and touches nothing else.
 */
@Service
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoGpuService implements DemoGpuUseCase {

    private static final Logger log = LoggerFactory.getLogger(DemoGpuService.class);

    /** {@code {"keepWarm": false}} is 19 bytes; anything near this is not a switch. */
    private static final int MAX_BODY_BYTES = 4 * 1024;

    private final DemoGpuControl control;
    private final ObjectMapper objectMapper;
    private final DemoPairingCode pairingCode;

    public DemoGpuService(DemoGpuControl control, ObjectMapper objectMapper, DemoProperties properties) {
        this.control = control;
        this.objectMapper = objectMapper;
        this.pairingCode = new DemoPairingCode(properties.pairingCode());
    }

    @Override
    public DemoGpuState gpu() {
        return upstream(control::state);
    }

    @Override
    public DemoGpuState keepWarm(String authorization, InputStream body) {
        pairingCode.require(authorization);
        boolean keepWarm = readKeepWarm(body);
        int workersMin = keepWarm ? 1 : 0;
        upstream(() -> {
            control.setWorkersMin(workersMin);
            return null;
        });
        log.info("[demo] GPU keepWarm set to {} (workersMin {})", keepWarm, workersMin);
        return upstream(control::state);
    }

    private boolean readKeepWarm(InputStream body) {
        byte[] bytes;
        try {
            bytes = body == null ? new byte[0] : body.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException exception) {
            throw keepWarmRequired();
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApplicationException(FailureKind.PAYLOAD_TOO_LARGE, "body_too_large", "body too large");
        }
        if (bytes.length == 0) {
            throw keepWarmRequired();
        }
        JsonNode value;
        try {
            value = objectMapper.readTree(bytes).path("keepWarm");
        } catch (JacksonException exception) {
            throw keepWarmRequired();
        }
        if (!value.isBoolean()) {
            throw keepWarmRequired();
        }
        return value.asBoolean();
    }

    private static ApplicationException keepWarmRequired() {
        return new ApplicationException(
                FailureKind.INVALID_INPUT, "keep_warm_required", "send {\"keepWarm\": true} or {\"keepWarm\": false}");
    }

    /** Any failure to reach the provider, or a refusal from it, is the provider's: a 502. */
    private static <T> T upstream(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException exception) {
            log.warn("[demo] GPU provider call failed: {}", exception.getMessage());
            throw new ApplicationException(
                    FailureKind.UPSTREAM_FAILURE, "demo_gpu_unavailable", "the GPU provider could not be reached");
        }
    }
}
