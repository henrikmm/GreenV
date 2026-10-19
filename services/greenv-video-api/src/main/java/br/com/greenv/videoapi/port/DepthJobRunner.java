package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.DepthJobRequest;
import br.com.greenv.videoapi.domain.DepthJobStatus;

/**
 * The GPU that turns frames into a reconstruction, as a job queue: submit, ask, give up.
 *
 * <p>Submitting is what costs money. A serverless endpoint bills for the whole lifetime of the
 * machine it wakes, so {@link #cancel} exists for the one case the pipeline can see coming: a job
 * still queued or running when its deadline passes.
 */
public interface DepthJobRunner {

    DepthJobStatus submit(DepthJobRequest request);

    /** Where the job stands. Throws when the question itself could not be asked; try again later. */
    DepthJobStatus status(String jobId);

    /** Best effort; a job that already ended is not an error. */
    void cancel(String jobId);
}
