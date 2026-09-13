package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.CaptureProperties;
import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureObjectKeys;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.CaptureSessionQuery;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.FrameReadings;
import br.com.greenv.videoapi.domain.MeasurementProjection;
import br.com.greenv.videoapi.domain.MeasurementQuery;
import br.com.greenv.videoapi.domain.SegmentQuery;
import br.com.greenv.videoapi.domain.SegmentTravel;
import br.com.greenv.videoapi.domain.MeasurementSummary;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.domain.SampledFrame;
import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.domain.Sentido;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.FrameReadingsReader;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.port.MeasurementProjectionReader;
import br.com.greenv.videoapi.port.SampledFrameReader;
import br.com.greenv.videoapi.port.SegmentTrackWriter;
import br.com.greenv.videoapi.port.SegmentWorkQueue;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CaptureSessionService implements CaptureSessionUseCase {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CaptureSessionService.class);

    private static final long MAXIMUM_MANIFEST_BYTES = 4 * 1024 * 1024;

    /**
     * The packet carries one position per sampled frame - 112 at most - beside the height grid, so
     * it is the same order of size as the manifest. The cap is here to bound a read, not to express
     * an expectation.
     */
    private static final long MAXIMUM_MEASUREMENT_BYTES = 4 * 1024 * 1024;

    /**
     * The cell grid is the largest document a segment produces - half a megabyte for a hundred
     * frames - and it is read only to derive a summary, never served.
     */
    private static final long MAXIMUM_ASSESSMENT_BYTES = 16 * 1024 * 1024;

    /** A published frame is a 1024 px JPEG, about 65 KB. This bounds a read, nothing more. */
    private static final long MAXIMUM_FRAME_BYTES = 8 * 1024 * 1024;

    private final CaptureSessionStore captureSessionStore;
    private final CaptureObjectStorage objectStorage;
    private final SegmentWorkQueue workQueue;
    private final CaptureProperties captureProperties;
    private final IdentifierGenerator identifierGenerator;
    private final MeasurementProjectionReader projectionReader;
    private final SampledFrameReader frameReader;
    private final FrameReadingsReader frameReadingsReader;
    private final SegmentTrackWriter trackWriter;
    private final Clock clock;

    public CaptureSessionService(
            CaptureSessionStore captureSessionStore,
            CaptureObjectStorage objectStorage,
            SegmentWorkQueue workQueue,
            CaptureProperties captureProperties,
            IdentifierGenerator identifierGenerator,
            MeasurementProjectionReader projectionReader,
            SampledFrameReader frameReader,
            FrameReadingsReader frameReadingsReader,
            SegmentTrackWriter trackWriter,
            Clock clock) {
        this.captureSessionStore = captureSessionStore;
        this.objectStorage = objectStorage;
        this.workQueue = workQueue;
        this.captureProperties = captureProperties;
        this.identifierGenerator = identifierGenerator;
        this.projectionReader = projectionReader;
        this.frameReader = frameReader;
        this.frameReadingsReader = frameReadingsReader;
        this.trackWriter = trackWriter;
        this.clock = clock;
    }

    @Override
    public CaptureSessionDocument create(
            UUID requestedSessionId,
            String deviceId,
            Instant requestedStartedAt,
            String requestedRodovia,
            String requestedSentido) {
        Instant now = clock.instant();
        String rodovia = rodovia(requestedRodovia);
        Sentido sentido = sentido(requestedSentido);
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
            // The rodovia and the sentido describe the capture; they do not identify it. An
            // offline phone retries this call until it lands, and refusing a retry that carries a
            // corrected road would block segments that are already uploading.
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
                null,
                rodovia,
                sentido);
        return captureSessionStore.createSession(session);
    }

    @Override
    public CaptureSessionSummary getSession(UUID sessionId) {
        var session = captureSessionStore.getSession(sessionId);
        return new CaptureSessionSummary(
                session,
                captureSessionStore.segmentCount(sessionId),
                captureSessionStore.readySegmentCount(sessionId),
                captureSessionStore.measuredSegmentCount(sessionId));
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
        // Read here rather than carried on the segment row: the road belongs to the session, and
        // the two workers downstream have no database to look it up in.
        CaptureSessionDocument session = captureSessionStore.getSession(segment.sessionId());
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
                    queuedAt,
                    session.rodovia(),
                    session.sentido() == null ? null : session.sentido().wireValue()));
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
        String packetKey =
                // Built here, never taken from the message: see CaptureObjectKeys.measurement.
                CaptureObjectKeys.measurement(announcement.sessionId(), announcement.segmentIndex());
        captureSessionStore.recordMeasurement(
                announcement.sessionId(),
                announcement.segmentIndex(),
                packetKey,
                announcement.runId(),
                announcement.mock(),
                announcement.measuredAt(),
                projectionOf(announcement.sessionId(), announcement.segmentIndex(), packetKey),
                clock.instant());
        deriveFrameReadings(announcement.sessionId(), announcement.segmentIndex());
        deriveTravelLengths(announcement.sessionId());
    }

    /**
     * How far each segment of this session actually went, recomputed for the whole session.
     *
     * <p>Not per segment, because the distance is not inside one. The phone emits a handful of
     * clustered fixes in ten seconds, so a segment's own track spans a tenth of what it covered;
     * the honest measure is between consecutive segments, and every new measurement gives the one
     * before it a neighbour it did not have. Cheap: a handful of rows and no storage reads.
     */
    private void deriveTravelLengths(UUID sessionId) {
        try {
            List<SegmentTravel.Fix> fixes = captureSessionStore.findSegments(sessionId).stream()
                    .map(segment -> new SegmentTravel.Fix(
                            segment.segmentIndex(),
                            segment.measurement().trackCenterLat(),
                            segment.measurement().trackCenterLon(),
                            segment.capturedAt(),
                            segment.durationMillis()))
                    .toList();
            Map<Integer, Double> lengths = SegmentTravel.lengths(fixes);
            if (!lengths.isEmpty()) {
                captureSessionStore.recordTrackLengths(sessionId, lengths);
            }
        } catch (RuntimeException failure) {
            // An area estimate is worth less than the measurement it decorates: a session that
            // cannot be measured for length still records its heights.
            log.warn("Could not derive travelled lengths for {}: {}", sessionId, failure.getMessage());
        }
    }

    /**
     * Turns the assessment into one row per photograph, once.
     *
     * <p>Here rather than on every request because the assessment is organised by cell: asking
     * what one frame saw costs the same walk as asking for all of them, and the answer never
     * changes after the run is published. A failure costs the rows and not the measurement —
     * the segment is still recorded, and the backfill picks the segment up on its next pass.
     */
    private void deriveFrameReadings(UUID sessionId, int segmentIndex) {
        byte[] assessment =
                readIfPresent(CaptureObjectKeys.measurementArtifact(sessionId, segmentIndex, "assessment.json"));
        List<FrameReadings> readings = frameReadingsReader.readAll(assessment);
        if (readings.isEmpty()) {
            return;
        }
        captureSessionStore.replaceFrameReadings(sessionId, segmentIndex, readings);
    }

    @Override
    public void deriveFrameReadingsFor(UUID sessionId, int segmentIndex) {
        deriveFrameReadings(sessionId, segmentIndex);
    }

    /**
     * Reads what the worker wrote and keeps the part a map can draw.
     *
     * <p>Done here rather than taken off the announcement so the summary and the packet cannot
     * disagree, and so re-reading storage is enough to repair it. A read that fails costs the
     * summary and not the measurement: the segment is still recorded as measured, and the packet
     * is still served by {@code measurement(...)}.
     */
    private MeasurementProjection projectionOf(UUID sessionId, int segmentIndex, String packetKey) {
        byte[] packet = readIfPresent(packetKey);
        byte[] assessment =
                readIfPresent(CaptureObjectKeys.measurementArtifact(sessionId, segmentIndex, "assessment.json"));
        return projectionReader.project(packet, assessment);
    }

    private byte[] readIfPresent(String objectKey) {
        try {
            return objectStorage.exists(objectKey) ? objectStorage.read(objectKey, MAXIMUM_ASSESSMENT_BYTES) : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The frames a segment published, with the camera position for each where it is known.
     *
     * <p>Reads the manifest rather than listing the bucket: the manifest is the publication
     * record, and a bucket listing would also return frames from a run that failed after writing
     * some of them.
     */
    @Override
    public byte[] track(UUID sessionId) {
        // The whole session on purpose. A drawing of a route with a page of it missing is not a
        // shorter drawing, it is a wrong one, and this is one document rather than a list a
        // reader scrolls.
        captureSessionStore.getSession(sessionId);
        return trackWriter.featureCollection(captureSessionStore.findSegments(sessionId));
    }

    @Override
    public List<SampledFrame> frames(UUID sessionId, int segmentIndex) {
        CaptureSegmentDocument segment = captureSessionStore.getSegment(sessionId, segmentIndex);
        if (segment.manifestObjectKey() == null || !objectStorage.exists(segment.manifestObjectKey())) {
            throw new ApplicationException(
                    FailureKind.CONFLICT, "segment_manifest_not_ready", "segment manifest is not ready");
        }
        byte[] manifest = objectStorage.read(segment.manifestObjectKey(), MAXIMUM_MANIFEST_BYTES);
        byte[] packet =
                segment.measurementObjectKey() == null ? null : readIfPresent(segment.measurementObjectKey());
        return frameReader.read(manifest, packet);
    }

    /**
     * One JPEG, by name.
     *
     * <p>The name is checked against the manifest before a key is built from it. A caller that
     * can name any object can read any object, and the manifest is the only list of names this
     * segment actually published.
     */
    @Override
    public byte[] frame(UUID sessionId, int segmentIndex, String fileName) {
        boolean published = frames(sessionId, segmentIndex).stream()
                .anyMatch(frame -> frame.fileName().equals(fileName));
        if (!published) {
            throw new ApplicationException(
                    FailureKind.NOT_FOUND, "sampled_frame_absent", "this segment published no such frame");
        }
        return objectStorage.read(
                CaptureObjectKeys.sampledFrame(sessionId, segmentIndex, fileName), MAXIMUM_FRAME_BYTES);
    }

    /**
     * One frame's votes, out of the assessment rather than the result envelope.
     *
     * <p>The name is checked against the manifest first, for the same reason the bytes
     * route checks it: a caller that can name any object can read any object. The canonical
     * frame number comes from the manifest entry and not from parsing the string again.
     *
     * <p>A missing or unreadable assessment answers with an empty reading, not an error. The
     * photograph exists either way, and the screen that asks this question is already
     * showing it.
     */
    @Override
    public FrameReadings frameReadings(UUID sessionId, int segmentIndex, String fileName) {
        var published = frames(sessionId, segmentIndex).stream()
                .filter(frame -> frame.fileName().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new ApplicationException(
                        FailureKind.NOT_FOUND,
                        "sampled_frame_absent",
                        "this segment published no such frame"));
        return captureSessionStore
                .findFrameReadings(sessionId, segmentIndex, published.canonicalFrame())
                // No row means the assessment named no vote for this frame, or the backfill has
                // not reached this segment yet. Both read as a frame that measured nothing, which
                // is visible on screen rather than silent.
                .orElseGet(() -> FrameReadings.empty(published.canonicalFrame()));
    }

    @Override
    public Page<CaptureSessionSummary> listSessions(CaptureSessionQuery query) {
        return captureSessionStore.findSessions(query);
    }

    @Override
    public Page<CaptureSegmentDocument> listSegments(SegmentQuery query) {
        // Rejects an unknown session rather than answering an empty list, so a mistyped id reads
        // as 404 and not as a session that exists and recorded nothing.
        captureSessionStore.getSession(query.sessionId());
        return captureSessionStore.findSegments(query);
    }

    @Override
    public MeasurementSummary summariseSegments(UUID sessionId) {
        captureSessionStore.getSession(sessionId);
        return captureSessionStore.summariseSegments(sessionId);
    }

    @Override
    public Page<CaptureSegmentDocument> listMeasurements(MeasurementQuery query) {
        return captureSessionStore.findMeasurements(query);
    }

    @Override
    public MeasurementSummary summariseMeasurements(MeasurementQuery query) {
        return captureSessionStore.summariseMeasurements(query);
    }

    @Override
    public int segmentSeconds() {
        return captureProperties.segmentSeconds();
    }

    /**
     * The rodovia, uppercased and bounded, or null.
     *
     * <p>Not pattern-matched. This repository carries no highway register to check a designation
     * against, and a guessed pattern would reject the state and municipal roads that are most of
     * the network. Case is normalised because {@code br-101} and {@code BR-101} joining as two
     * roads is the failure that actually happens.
     */
    private static String rodovia(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        if (normalised.length() > 32) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_rodovia", "rodovia must not exceed 32 characters");
        }
        return normalised;
    }

    private static Sentido sentido(String value) {
        try {
            return Sentido.of(value);
        } catch (IllegalArgumentException exception) {
            throw new ApplicationException(FailureKind.INVALID_INPUT, "invalid_sentido", exception.getMessage());
        }
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
