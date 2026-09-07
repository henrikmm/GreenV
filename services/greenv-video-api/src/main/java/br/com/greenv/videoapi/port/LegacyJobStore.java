package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.JobDocument;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Storage boundary for the deprecated whole-video workflow. */
public interface LegacyJobStore {

    JobDocument create(JobDocument job);

    JobDocument get(UUID jobId);

    JobDocument update(UUID jobId, UnaryOperator<JobDocument> updater);

    JobDocument storeSource(UUID jobId, InputStream input, Instant now);

    String statusReference(UUID jobId);

    String outputPrefixReference(UUID jobId);

    byte[] readManifest(UUID jobId);

    boolean manifestExists(UUID jobId);

    List<JobDocument> listJobs();

    void deleteJob(UUID jobId);

    void saveJob(UUID jobId, Instant now);
}
