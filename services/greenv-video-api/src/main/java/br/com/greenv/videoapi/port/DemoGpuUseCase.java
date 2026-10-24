package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.DemoGpuState;
import java.io.InputStream;

/**
 * Whether the demo keeps its GPU warm. Reading is public, like every demo read; changing it needs
 * the pairing code, exactly as deleting a capture does, because a warm GPU bills by the second.
 */
public interface DemoGpuUseCase {

    DemoGpuState gpu();

    /**
     * Reads {@code {"keepWarm": true|false}} from the body — only after the pairing code has been
     * checked — sets {@code workersMin} to 1 or 0, and answers with the state that follows.
     */
    DemoGpuState keepWarm(String authorization, InputStream body);
}
