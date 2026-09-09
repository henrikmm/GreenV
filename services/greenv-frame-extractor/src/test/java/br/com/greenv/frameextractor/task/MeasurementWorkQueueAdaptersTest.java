package br.com.greenv.frameextractor.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import br.com.greenv.frameextractor.config.CaptureQueueProperties;
import br.com.greenv.frameextractor.config.MeasurementQueueProperties;
import br.com.greenv.frameextractor.domain.MeasurementRequest;
import com.azure.core.exception.AzureException;
import com.azure.storage.queue.QueueClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MeasurementWorkQueueAdaptersTest {

    private static final UUID SESSION = UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * The one thing a second transport must not change. Worker 2 parses whatever arrives into the
     * same nine fields, so a queue swap that also became a contract change would fail in the only
     * deployment nobody runs locally.
     */
    @Test
    void bothTransportsAnnounceTheSameBytes() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        QueueClient queueClient = mock(QueueClient.class);

        new RabbitMqMeasurementWorkQueueAdapter(
                        rabbitTemplate,
                        objectMapper,
                        new CaptureQueueProperties("greenv.capture", "greenv.segment.extract.v2", "segment.extract.v2"),
                        new MeasurementQueueProperties(true, "greenv.segment.measure.v1", "segment.measure.v1"))
                .publish(request());
        new AzureQueueMeasurementWorkQueueAdapter(queueClient, objectMapper).publish(request());

        ArgumentCaptor<Object> overRabbit = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate)
                .convertAndSend(
                        eq("greenv.capture"),
                        eq("segment.measure.v1"),
                        overRabbit.capture(),
                        any(MessagePostProcessor.class));
        ArgumentCaptor<String> overAzure = ArgumentCaptor.forClass(String.class);
        verify(queueClient).sendMessage(overAzure.capture());

        assertThat(overAzure.getValue()).isEqualTo(overRabbit.getValue());
        assertThat(overAzure.getValue())
                .contains("\"schemaVersion\":1")
                .contains(SESSION.toString())
                // Azure Queue has no settable message id, so the idempotency key has to be in here.
                .contains("device:session:0");
    }

    /**
     * The port's invariant: a completed extraction is never failed by its announcement. The frames
     * are already durable in object storage and worker 2 can still be triggered over HTTP, so a
     * re-extraction would buy nothing and cost an ffmpeg run.
     */
    @Test
    void anUnreachableQueueDoesNotFailAnExtractionThatSucceeded() {
        QueueClient queueClient = mock(QueueClient.class);
        doThrow(new AzureException("queue is not there")).when(queueClient).sendMessage(anyString());

        assertThatCode(() -> new AzureQueueMeasurementWorkQueueAdapter(queueClient, objectMapper).publish(request()))
                .doesNotThrowAnyException();
    }

    private static MeasurementRequest request() {
        return new MeasurementRequest(
                MeasurementRequest.SCHEMA_VERSION,
                SESSION,
                0,
                "device:session:0",
                "capture-sessions/" + SESSION + "/segments/00000000",
                "a".repeat(64),
                40,
                Instant.parse("2026-08-25T11:59:50Z"),
                Instant.parse("2026-08-25T12:00:00Z"));
    }
}
