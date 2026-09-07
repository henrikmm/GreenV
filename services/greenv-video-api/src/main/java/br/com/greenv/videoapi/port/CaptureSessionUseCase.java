package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/** Inbound application operations for segmented mobile captures. */
public interface CaptureSessionUseCase {

    CaptureSessionDocument create(UUID requestedSessionId, String deviceId, Instant startedAt);

    CaptureSessionSummary getSession(UUID sessionId);

    CaptureSegmentDocument getSegment(UUID sessionId, int segmentIndex);

    CaptureSegmentDocument uploadVideo(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            String expectedSha256,
            InputStream input);

    CaptureSegmentDocument uploadTelemetry(
            UUID sessionId,
            int segmentIndex,
            String idempotencyKey,
            Instant capturedAt,
            long durationMillis,
            String expectedSha256,
            InputStream input);

    CaptureSegmentDocument completeSegment(UUID sessionId, int segmentIndex);

    CaptureSessionDocument completeSession(UUID sessionId, int lastSegmentIndex, Instant endedAt);

    byte[] manifest(UUID sessionId, int segmentIndex);

    int segmentSeconds();
}
