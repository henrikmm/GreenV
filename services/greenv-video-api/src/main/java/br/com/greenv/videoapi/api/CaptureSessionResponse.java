package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.CaptureSessionSummary;
import br.com.greenv.videoapi.domain.Sentido;
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
        long readySegmentCount,
        // How much of this session has a measurement. A list that shows only "ready" cannot tell
        // a session still being measured from one that is finished.
        long measuredSegmentCount,
        // The same session counted in readings — the 25 m stretches a crew is actually sent to.
        // A session line shows how much of it is overdue, and the sessions list sorts on it.
        long level1Count,
        long level2Count,
        long level3Count,
        // Readings with no level: measured with no usable cell, or not measured yet. Reported
        // apart from level 1, because an unknown height is not a short one.
        long unratedCount,
        // Where the session was, from its own readings: the street of the first one that resolved,
        // and how many streets it crossed altogether. Null when nothing resolved — the screen says
        // "sem posição" for that, and it would be wrong to say it just because a page did not load
        // the reading that knows.
        String placeLabel,
        String placeDetail,
        long placeLabelCount,
        String rodovia,
        Sentido sentido) {

    public static CaptureSessionResponse from(CaptureSessionSummary summary) {
        var session = summary.session();
        var readings = summary.readings();
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
                summary.readySegmentCount(),
                summary.measuredSegmentCount(),
                readings.level1Count(),
                readings.level2Count(),
                readings.level3Count(),
                readings.unratedCount(),
                summary.place().label(),
                summary.place().detail(),
                summary.place().distinctLabels(),
                session.rodovia(),
                session.sentido());
    }
}
