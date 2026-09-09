package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.CaptureProperties;
import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CaptureSessionService implements CaptureSessionUseCase {

    private static final long MAXIMUM_MANIFEST_BYTES = 4 * 1024 * 1024;

    /**
     * The packet carries one position per sampled frame - 112 at most - beside the height grid, so
     * it is the same order of size as the manifest. The cap is here to bound a read, not to express
     * an expectation.
     */
    private static final long MAXIMUM_MEASUREMENT_BYTES = 4 * 1024 * 1024;

    private final CaptureSessionStore captureSessionStore;
    private final CaptureObjectStorage objectStorage;
    private final SegmentWorkQueue workQueue;
    private final CaptureProperties captureProperties;
    private final IdentifierGenerator identifierGenerator;
    private final Clock clock;

    public CaptureSessionService(
            CaptureSessionStore captureSessionStore,
            CaptureObjectStorage objectStorage,
            SegmentWorkQueue workQueue,
            CaptureProperties captureProperties,
            IdentifierGenerator identifierGenerator,
            Clock clock) {
        this.captureSessionStore = captureSessionStore;
        this.objectStorage = objectStorage;
        this.workQueue = workQueue;
        this.captureProperties = captureProperties;
        this.identifierGenerator = identifierGenerator;
        this.clock = clock;
    }

    @Override
    public CaptureSessionDocument create(UUID requestedSessionId, String deviceId, Instant requestedStartedAt) {
        Instant now = clock.instant();
        Instant startedAt = requestedStartedAt == null ? now : requestedStartedAt;
        if (startedAt.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "invalid_capture_start",
                    "capture start cannot be more than five minutes in the future");
        }
        UUID sessionId = requestedSessionId == null ? identifierGenerator.next() : requestedSessionId;
        var existing = captureSessionStore.findSession(sessionId);
        if (existing.isPresent()) {
            CaptureSessionDocument session = existing.get();
            if (!session.deviceId().equals(deviceId.trim())
                    || !session.startedAt().equals(startedAt)) {
                throw new ApplicationException(
                        FailureKind.CONFLICT,
                        "capture_session_identity_conflict",
                        "session id already belongs to different capture metadata");
            }
            return session;
        }
        CaptureSessionDocument session = new CaptureSessionDocument(
                sessionId,
                deviceId.trim(),
                "recording",
                startedAt,
                null,
                now,
                now,
                now.plus(captureProperties.transientDays(), ChronoUnit.DAYS),
                null);
        return captureSessionStore.createSession(session);
    }

    @Override
    public CaptureSessionSummary getSession(UUID sessionId) {
        var session = captureSessionStore.getSession(sessionId);
        return new CaptureSessionSummary(
                session,
                captureSessionStore.segmentCount(sessionId),
                captureSessionStore.readySegmentCount(sessionId));
    }

    @Override
    public CaptureSegmentDocument getSegment(UUID sessionId, int segmentIndex) {
        return captureSessionStore.getSegment(sessionId, segmentIndex);
    }

    @Override
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
        CaptureSegmentDocument segment = captureSessionStore.ensureSegment(
                sessionId, segmentIndex, idempotencyKey, capturedAt, durationMillis, now);
        if (segment.videoSha256() != null && !segment.videoSha256().equals(expectedSha256)) {
            throw conflict("video");
        }
        var stored = objectStorage.put(
                CaptureObjectKeys.video(sessionId, segmentIndex),
                input,
                expectedSha256,
                captureProperties.maxSegmentBytes());
        return captureSessionStore.recordVideo(
                sessionId, segmentIndex, stored.objectKey(), stored.sha256(), stored.bytes(), now);
    }

    @Override
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
        CaptureSegmentDocument segment = captureSessionStore.ensureSegment(
                sessionId, segmentIndex, idempotencyKey, capturedAt, durationMillis, now);
        if (segment.telemetrySha256() != null && !segment.telemetrySha256().equals(expectedSha256)) {
            throw conflict("telemetry");
        }
        var stored = objectStorage.put(
                CaptureObjectKeys.telemetry(sessionId, segmentIndex),
                input,
                expectedSha256,
                captureProperties.maxTelemetryBytes());
        return captureSessionStore.recordTelemetry(
                sessionId, segmentIndex, stored.objectKey(), stored.sha256(), stored.bytes(), now);
    }

    @Override
    public CaptureSegmentDocument completeSegment(UUID sessionId, int segmentIndex) {
        CaptureSegmentDocument segment = captureSessionStore.getSegment(sessionId, segmentIndex);
        if (segment.state().equals("validating") || segment.state().equals("ready")) {
            return segment;
        }
        if (segment.state().equals("queued")) {
            publishSegment(segment);
            return segment;
        }
        if (!segment.hasBothUploads()) {
            throw new ApplicationException(
                    FailureKind.CONFLICT,
                    "segment_upload_incomplete",
                    "video and telemetry must both finish before extraction is queued");
        }
        Instant now = clock.instant();
        CaptureSegmentDocument queued = captureSessionStore.markQueued(sessionId, segmentIndex, now);
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
        } catch (ApplicationException exception) {
            captureSessionStore.markQueueFailed(
                    segment.sessionId(),
                    segment.segmentIndex(),
                    exception.code(),
                    exception.getMessage(),
                    clock.instant());
            throw exception;
        }
    }

    @Override
    public CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt) {
        if (lastSegmentIndex < 0) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_segment_index", "last segment index must be non-negative");
        }
        Instant now = clock.instant();
        return captureSessionStore.completeSession(
                sessionId,
                lastSegmentIndex,
                endedAt == null ? now : endedAt,
                now);
    }

    @Override
    public byte[] manifest(UUID sessionId, int segmentIndex) {
        CaptureSegmentDocument segment = captureSessionStore.getSegment(sessionId, segmentIndex);
        if (!"ready".equals(segment.state())
                || segment.manifestObjectKey() == null
                || !objectStorage.exists(segment.manifestObjectKey())) {
            throw new ApplicationException(
                    FailureKind.CONFLICT, "segment_manifest_not_ready", "segment manifest is not ready");
        }
        return objectStorage.read(segment.manifestObjectKey(), MAXIMUM_MANIFEST_BYTES);
    }

    @Override
    public byte[] measurement(UUID sessionId, int segmentIndex) {
        CaptureSegmentDocument segment = captureSessionStore.getSegment(sessionId, segmentIndex);
        if (segment.measurementObjectKey() == null
                || !objectStorage.exists(segment.measurementObjectKey())) {
            throw new ApplicationException(
                    FailureKind.CONFLICT,
                    "segment_measurement_not_ready",
                    "segment measurement is not ready");
        }
        return objectStorage.read(segment.measurementObjectKey(), MAXIMUM_MEASUREMENT_BYTES);
    }

    @Override
    public void recordMeasurement(SegmentMeasurementAnnouncement announcement) {
        if (announcement == null || !announcement.isUsable()) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "invalid_measurement_announcement",
                    "measurement announcement is incomplete");
        }
        // A segment this deployment has never heard of is a message from another life - a database
        // reset, a replayed queue. Recording it would invent a row; failing would make it poison
        // and block the queue behind it. Neither is worth it: the packet is still in storage.
        if (captureSessionStore.findSegment(announcement.sessionId(), announcement.segmentIndex()).isEmpty()) {
            return;
        }
        captureSessionStore.recordMeasurement(
                announcement.sessionId(),
                announcement.segmentIndex(),
                // Built here, never taken from the message: see CaptureObjectKeys.measurement.
                CaptureObjectKeys.measurement(announcement.sessionId(), announcement.segmentIndex()),
                announcement.runId(),
                announcement.mock(),
                announcement.measuredAt(),
                clock.instant());
    }

    @Override
    public int segmentSeconds() {
        return captureProperties.segmentSeconds();
    }

    private static void validateIdentity(int segmentIndex, String idempotencyKey, long durationMillis) {
        if (segmentIndex < 0) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_segment_index", "segment index must be non-negative");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "invalid_idempotency_key",
                    "X-Idempotency-Key is required and must not exceed 200 characters");
        }
        if (durationMillis <= 0 || durationMillis > 30_000) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "invalid_segment_duration",
                    "X-Duration-Millis must be between 1 and 30000");
        }
    }

    private static ApplicationException conflict(String object) {
        return new ApplicationException(
                FailureKind.CONFLICT,
                "segment_object_conflict",
                object + " was already uploaded with a different checksum");
    }
}
