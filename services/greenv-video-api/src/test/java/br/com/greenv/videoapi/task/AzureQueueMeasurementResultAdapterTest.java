package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.greenv.videoapi.config.AzureQueueProperties;
import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class AzureQueueMeasurementResultAdapterTest {

    private static final UUID SESSION = UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f");

    // The same envelope RabbitMqMeasurementResultAdapterTest reads, character for character. A
    // transport change that quietly became a contract change would show up here first.
    private static final String ENVELOPE =
            """
            {"schemaVersion":"greenv.measurement-result/1.0.0","sessionId":"%s","segmentIndex":3,
             "outputPrefix":"capture-sessions/%s/segments/00000003","runId":"20260908-000000-abcdef",
             "mock":false,"measuredAt":"2026-09-08T03:00:00Z",
             "positions":[{"canonicalFrame":1}],"measurement":{"quality":{}}}
            """
                    .formatted(SESSION, SESSION);

    private final QueueClient queueClient = mock(QueueClient.class);
    private final QueueClient poisonQueueClient = mock(QueueClient.class);
    private final CaptureSessionUseCase useCase = mock(CaptureSessionUseCase.class);

    private final AzureQueueMeasurementResultAdapter adapter = new AzureQueueMeasurementResultAdapter(
            queueClient,
            poisonQueueClient,
            new JacksonMeasurementAnnouncementReaderAdapter(JsonMapper.builder().build()),
            useCase,
            properties());

    @Test
    void recordsTheMeasurementAndThenDeletesTheMessage() {
        adapter.process(message(ENVELOPE, 1));

        ArgumentCaptor<SegmentMeasurementAnnouncement> announced =
                ArgumentCaptor.forClass(SegmentMeasurementAnnouncement.class);
        verify(useCase).recordMeasurement(announced.capture());
        assertThat(announced.getValue())
                .isEqualTo(SegmentMeasurementAnnouncement.ofSegment(
                        SESSION, 3, "20260908-000000-abcdef", false, Instant.parse("2026-09-08T03:00:00Z")));
        // Deleting is the acknowledgement. Doing it before the use case ran would lose the segment.
        verify(queueClient).deleteMessage("message-1", "receipt");
    }

    @Test
    void leavesAFailedAnnouncementForTheVisibilityTimeoutToRedeliver() {
        adapter.process(message("not json at all", 1));

        verify(useCase, never()).recordMeasurement(any());
        verify(queueClient, never()).deleteMessage(anyString(), anyString());
        verify(poisonQueueClient, never()).sendMessage(anyString());
    }

    @Test
    void keepsAnUnreadableAnnouncementOnceItsDeliveriesAreSpent() {
        // The RabbitMQ listener drops one of these because it has nowhere to put it. Here it stays
        // readable, which is the whole reason worker 1's poller was given a poison queue.
        adapter.process(message("not json at all", 5));

        verify(poisonQueueClient).sendMessage("not json at all");
        verify(queueClient).deleteMessage("message-1", "receipt");
    }

    private static QueueMessageItem message(String body, long dequeueCount) {
        QueueMessageItem message = mock(QueueMessageItem.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn("message-1");
        when(message.getPopReceipt()).thenReturn("receipt");
        when(message.getDequeueCount()).thenReturn(dequeueCount);
        return message;
    }

    private static AzureQueueProperties properties() {
        return new AzureQueueProperties(
                "greenv-segment-extract-v2",
                "UseDevelopmentStorage=true",
                null,
                false,
                1,
                300,
                "greenv-segment-measured-poison",
                5,
                "greenv-segment-measured-v1");
    }
}
