package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.JobDocument;
import br.com.greenv.videoapi.domain.JobState;
import br.com.greenv.videoapi.domain.Retention;
import br.com.greenv.videoapi.domain.SamplingOptions;
import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        int schemaVersion,
        UUID jobId,
        JobState state,
        Retention retention,
        String fileName,
        long declaredSizeBytes,
        Long actualSizeBytes,
        SamplingOptions sampling,
        Instant createdAt,
        Instant updatedAt,
        String uploadUrl,
        String statusUrl,
        String manifestUrl,
        boolean sourceDeleted,
        String errorCode,
        String errorMessage) {

    public static JobResponse from(JobDocument job, String baseUrl) {
        String jobUrl = baseUrl + "/v1/jobs/" + job.jobId();
        return new JobResponse(
                job.schemaVersion(),
                job.jobId(),
                job.state(),
                job.retention(),
                job.fileName(),
                job.declaredSizeBytes(),
                job.actualSizeBytes(),
                job.sampling(),
                job.createdAt(),
                job.updatedAt(),
                jobUrl + "/source",
                jobUrl,
                job.manifestUri() == null ? null : jobUrl + "/manifest",
                job.sourceDeleted(),
                job.errorCode(),
                job.errorMessage());
    }
}
