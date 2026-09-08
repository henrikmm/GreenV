package br.com.greenv.frameextractor.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.CaptureSegmentStore;
import br.com.greenv.frameextractor.port.SegmentProcessor;
import br.com.greenv.frameextractor.port.SegmentWorkQueue;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SegmentExtractionHandlerTest {

    @Test
    void republishesRetryableWorkThroughTheQueuePort() {
        SegmentProcessor segmentProcessor = mock(SegmentProcessor.class);
        CaptureSegmentStore segments = mock(CaptureSegmentStore.class);
        SegmentWorkQueue queue = mock(SegmentWorkQueue.class);
        SegmentExtractionRequest request = request(0);
        when(segments.state(request)).thenReturn("queued");
        when(segmentProcessor.extract(request)).thenThrow(new ExtractionException("temporary", "try again", true));

        handler(segmentProcessor, segments, queue).handle(request);

        verify(queue).publish(request.nextAttempt());
    }

    @Test
    void persistsTerminalFailureThroughTheDatabasePort() {
        SegmentProcessor segmentProcessor = mock(SegmentProcessor.class);
        CaptureSegmentStore segments = mock(CaptureSegmentStore.class);
        SegmentWorkQueue queue = mock(SegmentWorkQueue.class);
        SegmentExtractionRequest request = request(2);
        ExtractionException failure = new ExtractionException("invalid", "do not retry", false);
        when(segments.state(request)).thenReturn("queued");
        when(segmentProcessor.extract(request)).thenThrow(failure);

        handler(segmentProcessor, segments, queue).handle(request);

        verify(segments).markError(
                request,
                failure.code(),
                failure.getMessage(),
                Instant.parse("2026-08-25T12:00:00Z"));
    }

    private static SegmentExtractionHandler handler(
            SegmentProcessor segmentProcessor,
            CaptureSegmentStore segments,
            SegmentWorkQueue queue) {
        return new SegmentExtractionHandler(
                segmentProcessor,
                segments,
                queue,
                new ExtractorProperties(Path.of("build/test-pipeline"), "ffmpeg", "ffprobe", 300, 3, 1000, false),
                Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC));
    }

    private static SegmentExtractionRequest request(int attempt) {
        return new SegmentExtractionRequest(
                2,
                attempt,
                UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f"),
                0,
                "device:session:0",
                "capture-sessions/session/segments/00000000/source.mp4",
                "a".repeat(64),
                "capture-sessions/session/segments/00000000/telemetry.json",
                "b".repeat(64),
                "capture-sessions/session/segments/00000000",
                Instant.parse("2026-08-25T11:59:50Z"),
                10_000,
                Instant.parse("2026-08-25T12:00:00Z"));
    }
}
