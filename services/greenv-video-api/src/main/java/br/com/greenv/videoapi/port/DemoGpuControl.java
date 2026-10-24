package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.DemoGpuState;

/**
 * The demo GPU endpoint's own settings, as opposed to its jobs ({@link DepthJobRunner}).
 *
 * <p>Changing {@code workersMin} is what costs money here: one worker kept warm bills for every
 * second it is kept, whether or not a job arrives. Nothing but {@code workersMin} is ever written.
 * Both methods throw when the provider cannot be reached or refuses; the caller decides what that
 * means to its own client.
 */
public interface DemoGpuControl {

    DemoGpuState state();

    void setWorkersMin(int workersMin);
}
