package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.greenv.videoapi.config.AzureQueueProperties;
import br.com.greenv.videoapi.port.CaptureSessionUseCase;
import br.com.greenv.videoapi.port.MeasurementAnnouncementReader;
import com.azure.storage.queue.QueueClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which half of the control plane listens for a measurement, and when neither does.
 *
 * <p>Exactly one, never both: a deployment that publishes through Azure Queue must not also open a
 * Rabbit listener and retry a connection it has no reason to have, which is the failure that made
 * this conditional in the first place.
 */
class MeasurementResultAdapterSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AzureQueueMeasurementResultAdapter.class)
            .withBean("azureMeasuredQueueClient", QueueClient.class, () -> mock(QueueClient.class))
            .withBean("azureMeasuredPoisonQueueClient", QueueClient.class, () -> mock(QueueClient.class))
            .withBean(MeasurementAnnouncementReader.class, () -> mock(MeasurementAnnouncementReader.class))
            .withBean(CaptureSessionUseCase.class, () -> mock(CaptureSessionUseCase.class))
            .withBean(AzureQueueProperties.class, MeasurementResultAdapterSelectionTest::properties);

    @Test
    void anAzureQueueDeploymentThatNamesTheMeasuredQueuePollsIt() {
        contextRunner
                .withPropertyValues(
                        "greenv.adapters.segment-queue=azure-queue",
                        "greenv.queue.azure-queue.measured-queue=greenv-segment-measured-v1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AzureQueueMeasurementResultAdapter.class);
                });
    }

    @Test
    void noQueueNameMeansNoPoll() {
        // A deployment without worker 2 is a deployment that must not poll a queue nobody fills.
        contextRunner
                .withPropertyValues("greenv.adapters.segment-queue=azure-queue")
                .run(context -> assertThat(context).doesNotHaveBean(AzureQueueMeasurementResultAdapter.class));
        contextRunner
                .withPropertyValues(
                        "greenv.adapters.segment-queue=rabbitmq",
                        "greenv.queue.azure-queue.measured-queue=greenv-segment-measured-v1")
                .run(context -> assertThat(context).doesNotHaveBean(AzureQueueMeasurementResultAdapter.class));
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
