package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.domain.FrameManifest;
import br.com.greenv.frameextractor.port.LegacyFrameProcessor;
import br.com.greenv.frameextractor.port.LegacyPipelineStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyExtractionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    void returnsThePublishedManifestWithoutTouchingTheErrorStore() {
        LegacyFrameProcessor frameProcessor = mock(LegacyFrameProcessor.class);
        LegacyPipelineStore pipelineStore = mock(LegacyPipelineStore.class);
        FrameExtractionRequest request = request(0);
        FrameManifest manifest = mock(FrameManifest.class);
        when(frameProcessor.extract(request)).thenReturn(manifest);

        var result = handler(frameProcessor, pipelineStore).execute(request);

        assertThat(result.isReady()).isTrue();
        assertThat(result.manifest()).isSameAs(manifest);
        verifyNoInteractions(pipelineStore);
    }

    @Test
    void marksARetryableFailureAsQueuedWhileAttemptsRemain() {
        LegacyFrameProcessor frameProcessor = mock(LegacyFrameProcessor.class);
        LegacyPipelineStore pipelineStore = mock(LegacyPipelineStore.class);
        FrameExtractionRequest request = request(0);
        when(frameProcessor.extract(request))
                .thenThrow(new ExtractionException("temporary", "try again", true));

        var result = handler(frameProcessor, pipelineStore).execute(request);

        assertThat(result.shouldRetry()).isTrue();
        verify(pipelineStore).markError(request.statusUri(), "queued", "temporary", "try again", NOW);
    }

    @Test
    void marksTheLastAttemptAsFailed() {
        LegacyFrameProcessor frameProcessor = mock(LegacyFrameProcessor.class);
        LegacyPipelineStore pipelineStore = mock(LegacyPipelineStore.class);
        FrameExtractionRequest request = request(2);
        when(frameProcessor.extract(request))
                .thenThrow(new ExtractionException("temporary", "attempts exhausted", true));

        var result = handler(frameProcessor, pipelineStore).execute(request);

        assertThat(result.state()).isEqualTo("failed");
        verify(pipelineStore).markError(
                request.statusUri(), "failed", "temporary", "attempts exhausted", NOW);
    }

    private static LegacyExtractionHandler handler(
            LegacyFrameProcessor frameProcessor,
            LegacyPipelineStore pipelineStore) {
        return new LegacyExtractionHandler(
                frameProcessor,
                pipelineStore,
                new ExtractorProperties(Path.of("build/test-pipeline"), "ffmpeg", "ffprobe", 300, 3, 1000, false),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FrameExtractionRequest request(int attempt) {
        return new FrameExtractionRequest(
                1,
                attempt,
                UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f"),
                "job:generation",
                "file:///pipeline/source.mp4",
                "file:///pipeline/status.json",
                "file:///pipeline/output",
                "a".repeat(64),
                2.0,
                64,
                1280,
                NOW);
    }
}
