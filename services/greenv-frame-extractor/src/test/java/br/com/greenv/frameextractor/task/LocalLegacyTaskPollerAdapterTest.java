package br.com.greenv.frameextractor.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.port.LegacyExtractionResult;
import br.com.greenv.frameextractor.port.LegacyExtractionUseCase;
import br.com.greenv.frameextractor.port.LegacyTaskInbox;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalLegacyTaskPollerAdapterTest {

    @Test
    void acknowledgesSuccessfulWork() {
        LegacyTaskInbox taskInbox = mock(LegacyTaskInbox.class);
        LegacyExtractionUseCase extractionUseCase = mock(LegacyExtractionUseCase.class);
        var task = task();
        when(taskInbox.claim()).thenReturn(Optional.of(task));
        when(extractionUseCase.execute(task.request()))
                .thenReturn(LegacyExtractionResult.ready(mock(br.com.greenv.frameextractor.domain.FrameManifest.class)));

        new LocalLegacyTaskPollerAdapter(taskInbox, extractionUseCase).poll();

        verify(taskInbox).complete(task);
    }

    @Test
    void requeuesRetryableWork() {
        LegacyTaskInbox taskInbox = mock(LegacyTaskInbox.class);
        LegacyExtractionUseCase extractionUseCase = mock(LegacyExtractionUseCase.class);
        var task = task();
        when(taskInbox.claim()).thenReturn(Optional.of(task));
        when(extractionUseCase.execute(task.request()))
                .thenReturn(LegacyExtractionResult.failed("queued", "temporary", "try again"));

        new LocalLegacyTaskPollerAdapter(taskInbox, extractionUseCase).poll();

        verify(taskInbox).retry(task);
    }

    @Test
    void deadLettersTerminalWork() {
        LegacyTaskInbox taskInbox = mock(LegacyTaskInbox.class);
        LegacyExtractionUseCase extractionUseCase = mock(LegacyExtractionUseCase.class);
        var task = task();
        when(taskInbox.claim()).thenReturn(Optional.of(task));
        when(extractionUseCase.execute(task.request()))
                .thenReturn(LegacyExtractionResult.failed("failed", "invalid", "cannot process"));

        new LocalLegacyTaskPollerAdapter(taskInbox, extractionUseCase).poll();

        verify(taskInbox).fail(task);
    }

    private static LegacyTaskInbox.ClaimedTask task() {
        return new LegacyTaskInbox.ClaimedTask(
                "opaque-receipt",
                new FrameExtractionRequest(
                        1,
                        0,
                        UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f"),
                        "job:generation",
                        "file:///pipeline/source.mp4",
                        "file:///pipeline/status.json",
                        "file:///pipeline/output",
                        "a".repeat(64),
                        2.0,
                        64,
                        1280,
                        Instant.parse("2026-08-25T12:00:00Z")));
    }
}
