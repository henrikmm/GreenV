package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.FrameExtractionRequest;
import br.com.greenv.videoapi.domain.JobDocument;
import br.com.greenv.videoapi.domain.JobState;
import br.com.greenv.videoapi.domain.SamplingOptions;
import br.com.greenv.videoapi.port.FrameWorkQueue;
import br.com.greenv.videoapi.port.LegacyJobStore;
import br.com.greenv.videoapi.port.LegacyJobUseCase;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobService implements LegacyJobUseCase {

    private final LegacyJobStore jobStore;
    private final FrameWorkQueue frameWorkQueue;
    private final PipelineProperties pipelineProperties;
    private final Clock clock;

    public JobService(
            LegacyJobStore jobStore,
            FrameWorkQueue frameWorkQueue,
            PipelineProperties pipelineProperties,
            Clock clock) {
        this.jobStore = jobStore;
        this.frameWorkQueue = frameWorkQueue;
        this.pipelineProperties = pipelineProperties;
        this.clock = clock;
    }

    @Override
    public JobDocument create(String fileName, String contentType, long sizeBytes, SamplingOptions sampling) {
        if (sizeBytes > pipelineProperties.maxFileSizeBytes()) {
            throw new ApplicationException(
                    FailureKind.PAYLOAD_TOO_LARGE, "video_too_large", "video exceeds the 1 GB limit");
        }
        Instant now = clock.instant();
        return jobStore.create(JobDocument.create(
                UUID.randomUUID(),
                safeFileName(fileName),
                contentType,
                sizeBytes,
                sampling,
                now));
    }

    @Override
    public JobDocument get(UUID jobId) {
        return jobStore.get(jobId);
    }

    @Override
    public JobDocument upload(UUID jobId, InputStream input) {
        return jobStore.storeSource(jobId, input, clock.instant());
    }

    @Override
    public JobDocument complete(UUID jobId) {
        JobDocument job = jobStore.get(jobId);
        if (job.state() == JobState.QUEUED
                || job.state() == JobState.EXTRACTING
                || job.state() == JobState.FRAMES_READY) {
            return job;
        }
        if (job.state() != JobState.UPLOADING || job.sourceGeneration() == null) {
            throw new ApplicationException(
                    FailureKind.CONFLICT,
                    "source_not_ready",
                    "source upload must finish before extraction is queued");
        }

        Instant now = clock.instant();
        JobDocument queued = jobStore.update(jobId, current -> current.withState(JobState.QUEUED, now));
        try {
            frameWorkQueue.publish(new FrameExtractionRequest(
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

    @Override
    public byte[] manifest(UUID jobId) {
        JobDocument job = jobStore.get(jobId);
        if (job.state() != JobState.FRAMES_READY || !jobStore.manifestExists(jobId)) {
            throw new ApplicationException(FailureKind.CONFLICT, "manifest_not_ready", "frame manifest is not ready");
        }
        return jobStore.readManifest(jobId);
    }

    @Override
    public JobDocument save(UUID jobId) {
        jobStore.saveJob(jobId, clock.instant());
        return jobStore.get(jobId);
    }

    @Override
    public void delete(UUID jobId) {
        jobStore.get(jobId);
        jobStore.deleteJob(jobId);
    }

    private static String safeFileName(String value) {
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) {
            throw new ApplicationException(FailureKind.INVALID_INPUT, "invalid_file_name", "file name is empty");
        }
        return name;
    }
}
