package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

public record JobDocument(
        int schemaVersion,
        UUID jobId,
        String fileName,
        String contentType,
        long declaredSizeBytes,
        Long actualSizeBytes,
        JobState state,
        Retention retention,
        SamplingOptions sampling,
        Instant createdAt,
        Instant updatedAt,
        String sourceUri,
        String sourceGeneration,
        String manifestUri,
        boolean sourceDeleted,
        String errorCode,
        String errorMessage) {

    public static JobDocument create(
            UUID jobId,
            String fileName,
            String contentType,
            long declaredSizeBytes,
            SamplingOptions sampling,
            Instant now) {
        return new JobDocument(
                1,
                jobId,
                fileName,
                contentType,
                declaredSizeBytes,
                null,
                JobState.CREATED,
                Retention.TRANSIENT,
                sampling,
                now,
                now,
                null,
                null,
                null,
                false,
                null,
                null);
    }

    public JobDocument withState(JobState nextState, Instant now) {
        return copy(nextState, retention, actualSizeBytes, sourceUri, sourceGeneration,
                manifestUri, sourceDeleted, null, null, now);
    }

    public JobDocument withUpload(long bytes, String uri, String generation, Instant now) {
        return copy(JobState.UPLOADING, retention, bytes, uri, generation,
                manifestUri, false, null, null, now);
    }

    public JobDocument withRetention(Retention nextRetention, Instant now) {
        return copy(state, nextRetention, actualSizeBytes, sourceUri, sourceGeneration,
                manifestUri, sourceDeleted, errorCode, errorMessage, now);
    }

    public JobDocument withError(String code, String message, Instant now) {
        return copy(JobState.FAILED, retention, actualSizeBytes, sourceUri, sourceGeneration,
                manifestUri, sourceDeleted, code, message, now);
    }

    private JobDocument copy(
            JobState nextState,
            Retention nextRetention,
            Long nextActualSize,
            String nextSourceUri,
            String nextSourceGeneration,
            String nextManifestUri,
            boolean nextSourceDeleted,
            String nextErrorCode,
            String nextErrorMessage,
            Instant now) {
        return new JobDocument(
                schemaVersion,
                jobId,
                fileName,
                contentType,
                declaredSizeBytes,
                nextActualSize,
                nextState,
                nextRetention,
                sampling,
                createdAt,
                now,
                nextSourceUri,
                nextSourceGeneration,
                nextManifestUri,
                nextSourceDeleted,
                nextErrorCode,
                nextErrorMessage);
    }
}
