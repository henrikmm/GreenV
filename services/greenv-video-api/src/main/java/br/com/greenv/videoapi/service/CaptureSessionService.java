package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.api.ApiException;
import br.com.greenv.videoapi.api.CaptureSessionResponse;
import br.com.greenv.videoapi.api.CreateCaptureSessionRequest;
import br.com.greenv.videoapi.config.CaptureProperties;
import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CaptureSessionService {

    private static final long MAXIMUM_MANIFEST_BYTES = 4 * 1024 * 1024;

    private final CaptureSessionStore repository;
    private final CaptureObjectStorage objectStorage;
    private final SegmentWorkQueue workQueue;
    private final CaptureProperties captureProperties;
    private final Clock clock;

    public CaptureSessionService(
            CaptureSessionStore repository,
            CaptureObjectStorage objectStorage,
            SegmentWorkQueue workQueue,
            CaptureProperties captureProperties,
            Clock clock) {
        this.repository = repository;
        this.objectStorage = objectStorage;
        this.workQueue = workQueue;
        this.captureProperties = captureProperties;
        this.clock = clock;
    }

    public CaptureSessionDocument create(CreateCaptureSessionRequest request) {
        Instant now = clock.instant();
        Instant startedAt = request.startedAt() == null ? now : request.startedAt();
        if (startedAt.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_capture_start",
                    "capture start cannot be more than five minutes in the future");
        }
        UUID sessionId = request.sessionId() == null ? UUID.randomUUID() : request.sessionId();
        var existing = repository.findSession(sessionId);
        if (existing.isPresent()) {
            CaptureSessionDocument session = existing.get();
            if (!session.deviceId().equals(request.deviceId().trim())
                    || !session.startedAt().equals(startedAt)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "capture_session_identity_conflict",
                        "session id already belongs to different capture metadata");
            }
            return session;
        }
        CaptureSessionDocument session = new CaptureSessionDocument(
                sessionId,
                request.deviceId().trim(),
                "recording",
                startedAt,
                null,
                now,
                now,
                now.plus(captureProperties.transientDays(), ChronoUnit.DAYS),
                null);
        return repository.createSession(session);
    }

    public CaptureSessionResponse getSession(UUID sessionId) {
        var session = repository.getSession(sessionId);
        return CaptureSessionResponse.from(
                session,
                repository.segmentCount(sessionId),
                repository.readySegmentCount(sessionId));
    }

    public CaptureSegmentDocument getSegment(UUID sessionId, int segmentIndex) {
        return repository.getSegment(sessionId, segmentIndex);
    }

    public CaptureSegmentDocument uploadVideo(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            String expectedSha256,
            InputStream input) {
        validateIdentity(segmentIndex, idempotencyKey, durationMillis);
        Instant now = clock.instant();
        CaptureSegmentDocument segment = repository.ensureSegment(
                sessionId, segmentIndex, idempotencyKey, capturedAt, durationMillis, now);
        if (segment.videoSha256() != null && !segment.videoSha256().equals(expectedSha256)) {
            throw conflict("video");
        }
        var stored = objectStorage.put(
                CaptureObjectKeys.video(sessionId, segmentIndex),
                input,
                expectedSha256,
                captureProperties.maxSegmentBytes());
        return repository.recordVideo(
                sessionId, segmentIndex, stored.objectKey(), stored.sha256(), stored.bytes(), now);
    }

    public CaptureSegmentDocument uploadTelemetry(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            String expectedSha256,
            InputStream input) {
        validateIdentity(segmentIndex, idempotencyKey, durationMillis);
        Instant now = clock.instant();
        CaptureSegmentDocument segment = repository.ensureSegment(
                sessionId, segmentIndex, idempotencyKey, capturedAt, durationMillis, now);
        if (segment.telemetrySha256() != null && !segment.telemetrySha256().equals(expectedSha256)) {
            throw conflict("telemetry");
        }
        var stored = objectStorage.put(
                CaptureObjectKeys.telemetry(sessionId, segmentIndex),
                input,
                expectedSha256,
                captureProperties.maxTelemetryBytes());
        return repository.recordTelemetry(
                sessionId, segmentIndex, stored.objectKey(), stored.sha256(), stored.bytes(), now);
    }

    public CaptureSegmentDocument completeSegment(UUID sessionId, int segmentIndex) {
        CaptureSegmentDocument segment = repository.getSegment(sessionId, segmentIndex);
        if (segment.state().equals("validating") || segment.state().equals("ready")) {
            return segment;
        }
        if (segment.state().equals("queued")) {
            publishSegment(segment);
            return segment;
        }
        if (!segment.hasBothUploads()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "segment_upload_incomplete",
                    "video and telemetry must both finish before extraction is queued");
        }
        Instant now = clock.instant();
        CaptureSegmentDocument queued = repository.markQueued(sessionId, segmentIndex, now);
        publishSegment(queued);
        return queued;
    }

    private void publishSegment(CaptureSegmentDocument segment) {
        Instant queuedAt = clock.instant();
        try {
            workQueue.publish(new SegmentExtractionRequest(
                    2,
                    0,
                    segment.sessionId(),
                    segment.segmentIndex(),
                    segment.idempotencyKey(),
                    segment.videoObjectKey(),
                    segment.videoSha256(),
                    segment.telemetryObjectKey(),
                    segment.telemetrySha256(),
                    CaptureObjectKeys.segmentPrefix(segment.sessionId(), segment.segmentIndex()),
                    segment.capturedAt(),
                    segment.durationMillis(),
                    queuedAt));
        } catch (ApiException exception) {
            repository.markQueueFailed(
                    segment.sessionId(),
                    segment.segmentIndex(),
                    exception.code(),
                    exception.getMessage(),
                    clock.instant());
            throw exception;
        }
    }

    public CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt) {
        if (lastSegmentIndex < 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "invalid_segment_index", "last segment index must be non-negative");
        }
        Instant now = clock.instant();
        return repository.completeSession(
                sessionId,
                lastSegmentIndex,
                endedAt == null ? now : endedAt,
                now);
    }

    public byte[] manifest(UUID sessionId, int segmentIndex) {
        CaptureSegmentDocument segment = repository.getSegment(sessionId, segmentIndex);
        if (!"ready".equals(segment.state())
                || segment.manifestObjectKey() == null
                || !objectStorage.exists(segment.manifestObjectKey())) {
            throw new ApiException(HttpStatus.CONFLICT, "segment_manifest_not_ready", "segment manifest is not ready");
        }
        return objectStorage.read(segment.manifestObjectKey(), MAXIMUM_MANIFEST_BYTES);
    }

    public int segmentSeconds() {
        return captureProperties.segmentSeconds();
    }

    private static void validateIdentity(int segmentIndex, String idempotencyKey, long durationMillis) {
        if (segmentIndex < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_segment_index", "segment index must be non-negative");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_idempotency_key",
                    "X-Idempotency-Key is required and must not exceed 200 characters");
        }
        if (durationMillis <= 0 || durationMillis > 30_000) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_segment_duration",
                    "X-Duration-Millis must be between 1 and 30000");
        }
    }

    private static ApiException conflict(String object) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "segment_object_conflict",
                object + " was already uploaded with a different checksum");
    }
}
