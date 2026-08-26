package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.api.ApiException;
import br.com.greenv.videoapi.api.CreateJobRequest;
import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.FrameExtractionRequest;
import br.com.greenv.videoapi.domain.JobDocument;
import br.com.greenv.videoapi.domain.JobState;
import br.com.greenv.videoapi.port.FrameWorkQueue;
import br.com.greenv.videoapi.port.LegacyJobStore;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    private final LegacyJobStore jobStore;
    private final FrameWorkQueue taskPublisher;
    private final PipelineProperties properties;
    private final Clock clock;

    public JobService(
            LegacyJobStore jobStore,
            FrameWorkQueue taskPublisher,
            PipelineProperties properties,
            Clock clock) {
        this.jobStore = jobStore;
        this.taskPublisher = taskPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    public JobDocument create(CreateJobRequest request) {
        if (request.sizeBytes() > properties.maxFileSizeBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "video_too_large", "video exceeds the 1 GB limit");
        }
        Instant now = clock.instant();
        return jobStore.create(JobDocument.create(
                UUID.randomUUID(),
                safeFileName(request.fileName()),
                request.contentType(),
                request.sizeBytes(),
                request.sampling(),
                now));
    }

    public JobDocument get(UUID jobId) {
        return jobStore.get(jobId);
    }

    public JobDocument upload(UUID jobId, InputStream input) {
        return jobStore.storeSource(jobId, input, clock.instant());
    }

    public JobDocument complete(UUID jobId) {
        JobDocument job = jobStore.get(jobId);
        if (job.state() == JobState.QUEUED
                || job.state() == JobState.EXTRACTING
                || job.state() == JobState.FRAMES_READY) {
            return job;
        }
        if (job.state() != JobState.UPLOADING || job.sourceGeneration() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "source_not_ready",
                    "source upload must finish before extraction is queued");
        }

        Instant now = clock.instant();
        JobDocument queued = jobStore.update(jobId, current -> current.withState(JobState.QUEUED, now));
        try {
            taskPublisher.publish(new FrameExtractionRequest(
                    1,
                    0,
                    job.jobId(),
                    job.jobId() + ":" + job.sourceGeneration(),
                    job.sourceUri(),
                    jobStore.statusReference(jobId),
                    jobStore.outputPrefixReference(jobId),
                    job.sourceGeneration(),
                    job.sampling().requestedFps(),
                    job.sampling().maxFrames(),
                    job.sampling().longEdge(),
                    now));
            return queued;
        } catch (RuntimeException exception) {
            jobStore.update(jobId, current -> current.withState(JobState.UPLOADING, clock.instant()));
            throw exception;
        }
    }

    public byte[] manifest(UUID jobId) {
        JobDocument job = jobStore.get(jobId);
        if (job.state() != JobState.FRAMES_READY || !jobStore.manifestExists(jobId)) {
            throw new ApiException(HttpStatus.CONFLICT, "manifest_not_ready", "frame manifest is not ready");
        }
        return jobStore.readManifest(jobId);
    }

    public JobDocument save(UUID jobId) {
        jobStore.saveJob(jobId, clock.instant());
        return jobStore.get(jobId);
    }

    public void delete(UUID jobId) {
        jobStore.get(jobId);
        jobStore.deleteJob(jobId);
    }

    private static String safeFileName(String value) {
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_file_name", "file name is empty");
        }
        return name;
    }
}
