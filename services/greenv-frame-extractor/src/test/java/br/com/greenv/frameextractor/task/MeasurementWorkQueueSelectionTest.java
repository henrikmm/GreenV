package br.com.greenv.frameextractor.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.greenv.frameextractor.config.CaptureQueueProperties;
import br.com.greenv.frameextractor.config.MeasurementQueueProperties;
import br.com.greenv.frameextractor.port.MeasurementWorkQueue;
import com.azure.storage.queue.QueueClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The port must have exactly one implementation, whatever the deployment is configured with.
 *
 * <p>Two would fail the context with an ambiguity and none would fail it outright, and either way
 * the worker does not start — which is how every uploaded segment once ended up sitting in
 * {@code queued}. The annotations alone cannot show this: it is the three conditions together that
 * either cover every combination once or do not.
 */
class MeasurementWorkQueueSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    RabbitMqMeasurementWorkQueueAdapter.class,
                    AzureQueueMeasurementWorkQueueAdapter.class,
                    DisabledMeasurementWorkQueueAdapter.class)
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withBean("azureMeasurementQueueClient", QueueClient.class, () -> mock(QueueClient.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(
                    CaptureQueueProperties.class,
                    () -> new CaptureQueueProperties("greenv.capture", "greenv.segment.extract.v2", "segment.extract.v2"))
            .withBean(
                    MeasurementQueueProperties.class,
                    () -> new MeasurementQueueProperties(true, "greenv.segment.measure.v1", "segment.measure.v1"));

    @Test
    void everyTransportGetsExactlyOnePublisher() {
        // No transport is left without one, including the two nothing deploys today: an sqs or
        // azure-service-bus stack keeps the RabbitMQ publisher it has always had rather than
        // gaining a new way to fail at startup.
        for (String transport : List.of("rabbitmq", "sqs", "azure-service-bus", "azure-queue")) {
            contextRunner
                    .withPropertyValues(
                            "greenv.measurement.enabled=true", "greenv.adapters.segment-queue=" + transport)
                    .run(context -> {
                        assertThat(context).as(transport).hasNotFailed();
                        assertThat(context).as(transport).hasSingleBean(MeasurementWorkQueue.class);
                    });
        }
    }

    @Test
    void azureQueueAnnouncesThroughAzureAndNotThroughRabbit() {
        contextRunner
                .withPropertyValues("greenv.measurement.enabled=true", "greenv.adapters.segment-queue=azure-queue")
                .run(context -> {
                    assertThat(context).hasSingleBean(AzureQueueMeasurementWorkQueueAdapter.class);
                    assertThat(context).doesNotHaveBean(RabbitMqMeasurementWorkQueueAdapter.class);
                });
    }

    @Test
    void aDeploymentWithoutWorkerTwoStillHasThePort() {
        // Unchanged: the extractor must run where nothing measures, and the default is exactly that.
        contextRunner.withPropertyValues("greenv.adapters.segment-queue=azure-queue").run(context -> {
            assertThat(context).hasSingleBean(DisabledMeasurementWorkQueueAdapter.class);
            assertThat(context).hasSingleBean(MeasurementWorkQueue.class);
        });
    }
}
