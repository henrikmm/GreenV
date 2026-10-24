package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.DemoGpuState;
import br.com.greenv.videoapi.port.DemoGpuControl;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * An endpoint that costs nothing and wakes nothing, for tests and for running the demo on one
 * laptop, selected with the mock depth adapter. It starts cold, as the real one is configured,
 * and a warm one reports its one kept worker as idle. Every {@code workersMin} written is
 * recorded, so a test can see exactly what would have been sent.
 */
@Component
@ConditionalOnExpression("${greenv.demo.enabled:false} and '${greenv.demo.depth-adapter:runpod}' == 'mock'")
public class MockDemoGpuControlAdapter implements DemoGpuControl {

    static final int WORKERS_MAX = 1;
    static final int IDLE_TIMEOUT_SECONDS = 5;

    private final AtomicInteger workersMin = new AtomicInteger();
    private final List<Integer> written = new CopyOnWriteArrayList<>();

    @Override
    public DemoGpuState state() {
        int min = workersMin.get();
        return new DemoGpuState(
                min, WORKERS_MAX, IDLE_TIMEOUT_SECONDS, new DemoGpuState.Workers(min, 0, 0, 0, 0, 0));
    }

    @Override
    public void setWorkersMin(int value) {
        written.add(value);
        workersMin.set(value);
    }

    /** Every {@code workersMin} written, oldest first. */
    public List<Integer> written() {
        return List.copyOf(written);
    }
}
