package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.CaptureSessionSummary;
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

    public static CaptureSessionResponse from(CaptureSessionSummary summary) {
        var session = summary.session();
        return new CaptureSessionResponse(
                1,
                session.sessionId(),
                session.deviceId(),
                session.state(),
                session.startedAt(),
                session.endedAt(),
                session.expiresAt(),
                session.lastSegmentIndex(),
                summary.segmentCount(),
                summary.readySegmentCount());
    }
}
