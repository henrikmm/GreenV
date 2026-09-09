package br.com.greenv.frameextractor.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.config.SqsQueueProperties;
import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.queue.QueueClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class CloudSegmentWorkQueueAdaptersTest {

    @Test
    void publishesRetryToSqs() {
        SqsClient client = mock(SqsClient.class);
        SqsQueueProperties properties = new SqsQueueProperties(
                "https://sqs.example/segment", "us-east-1", "", "", "", "segments", 1, 10, 300);

        new SqsSegmentWorkQueueAdapter(client, codec(), properties).publish(request());

        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(sent.capture());
        assertThat(sent.getValue().messageBody()).isEqualTo("{\"schemaVersion\":2}");
    }

    @Test
    void publishesRetryToAzureQueue() {
        QueueClient client = mock(QueueClient.class);

        new AzureQueueSegmentWorkQueueAdapter(client, codec()).publish(request());

        verify(client).sendMessage("{\"schemaVersion\":2}");
    }

    @Test
    void publishesRetryToAzureServiceBus() {
        ServiceBusSenderClient client = mock(ServiceBusSenderClient.class);
        SegmentExtractionRequest request = request();

        new AzureServiceBusSegmentWorkQueueAdapter(client, codec()).publish(request);

        ArgumentCaptor<ServiceBusMessage> sent = ArgumentCaptor.forClass(ServiceBusMessage.class);
        verify(client).sendMessage(sent.capture());
        assertThat(sent.getValue().getMessageId()).isEqualTo(request.idempotencyKey());
        assertThat(sent.getValue().getBody().toString()).isEqualTo("{\"schemaVersion\":2}");
    }

    static SegmentMessageCodec codec() {
        SegmentMessageCodec codec = mock(SegmentMessageCodec.class);
        when(codec.encode(any())).thenReturn("{\"schemaVersion\":2}");
        when(codec.decode(any())).thenReturn(request());
        return codec;
    }

    static SegmentExtractionRequest request() {
        return new SegmentExtractionRequest(
                2,
                0,
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
                Instant.parse("2026-08-25T12:00:00Z"),
                "BR-101",
                "sul");
    }
}
