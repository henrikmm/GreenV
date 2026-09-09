package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class RabbitMqMeasurementResultAdapterTest {

    private final CaptureSessionUseCase useCase = mock(CaptureSessionUseCase.class);
    private final RabbitMqMeasurementResultAdapter adapter =
            new RabbitMqMeasurementResultAdapter(
                    useCase, new JacksonMeasurementAnnouncementReaderAdapter(JsonMapper.builder().build()));

    @Test
    void readsTheIdentityOutOfTheWorkersWholeEnvelope() {
        UUID sessionId = UUID.randomUUID();

        // The real message carries the height grid, the per-frame positions and the depth
        // provenance too. Everything the control plane needs is these five fields.
        adapter.onMeasured("""
                {"schemaVersion":"greenv.measurement-result/1.0.0","sessionId":"%s","segmentIndex":3,
                 "outputPrefix":"capture-sessions/%s/segments/00000003","runId":"20260908-000000-abcdef",
                 "mock":false,"measuredAt":"2026-09-08T03:00:00Z",
                 "positions":[{"canonicalFrame":1}],"measurement":{"quality":{}}}
                """.formatted(sessionId, sessionId));

        ArgumentCaptor<SegmentMeasurementAnnouncement> announced =
                ArgumentCaptor.forClass(SegmentMeasurementAnnouncement.class);
        verify(useCase).recordMeasurement(announced.capture());
        assertThat(announced.getValue()).isEqualTo(new SegmentMeasurementAnnouncement(
                sessionId, 3, "20260908-000000-abcdef", false, Instant.parse("2026-09-08T03:00:00Z")));
    }

    @Test
    void dropsAMessageItCannotRead() {
        // Rejecting it would put it back on the queue for ever, and no retry can make it parse.
        adapter.onMeasured("{\"sessionId\":\"not-a-uuid\"}");
        adapter.onMeasured("not json at all");

        verify(useCase, never()).recordMeasurement(any());
    }
}
