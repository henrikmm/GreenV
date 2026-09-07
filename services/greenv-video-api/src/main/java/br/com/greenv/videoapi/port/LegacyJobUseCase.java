package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.JobDocument;
import br.com.greenv.videoapi.domain.SamplingOptions;
import java.io.InputStream;
import java.util.UUID;

/** Inbound application operations for the deprecated whole-video workflow. */
public interface LegacyJobUseCase {

    JobDocument create(String fileName, String contentType, long sizeBytes, SamplingOptions sampling);

    JobDocument get(UUID jobId);

    JobDocument upload(UUID jobId, InputStream input);

    JobDocument complete(UUID jobId);

    byte[] manifest(UUID jobId);

    JobDocument save(UUID jobId);

    void delete(UUID jobId);
}
