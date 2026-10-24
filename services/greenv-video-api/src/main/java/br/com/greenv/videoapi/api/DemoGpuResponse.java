package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.DemoGpuState;

/** The demo GPU's warm switch, its two other limits, and its workers by state. */
public record DemoGpuResponse(
        boolean keepWarm, int workersMin, int workersMax, int idleTimeoutSeconds, DemoGpuState.Workers workers) {

    public static DemoGpuResponse from(DemoGpuState state) {
        return new DemoGpuResponse(
                state.keepWarm(), state.workersMin(), state.workersMax(), state.idleTimeoutSeconds(), state.workers());
    }
}
