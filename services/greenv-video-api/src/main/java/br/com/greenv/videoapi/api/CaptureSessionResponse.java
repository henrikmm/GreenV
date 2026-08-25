package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.CaptureSessionDocument;
import java.time.Instant;
import java.util.UUID;

public record CaptureSessionResponse(
        int schemaVersion,
        UUID sessionId,
        String deviceId,
        String state,
        Instant startedAt,
        Instant endedAt,
        Instant expiresAt,
        Integer lastSegmentIndex,
        long segmentCount,
        long readySegmentCount) {

    public static CaptureSessionResponse from(
            CaptureSessionDocument session,
            long segmentCount,
            long readySegmentCount) {
        return new CaptureSessionResponse(
                1,
                session.sessionId(),
                session.deviceId(),
                session.state(),
                session.startedAt(),
                session.endedAt(),
                session.expiresAt(),
                session.lastSegmentIndex(),
                segmentCount,
                readySegmentCount);
    }
}
