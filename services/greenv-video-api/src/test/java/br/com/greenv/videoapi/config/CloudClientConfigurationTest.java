package br.com.greenv.videoapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.storage.queue.QueueClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

class CloudClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CloudClientConfiguration.class)
            .withBean(
                    S3StorageProperties.class,
                    () -> new S3StorageProperties(
                            "greenv-captures",
                            "auto",
                            "https://account.r2.cloudflarestorage.com",
                            false,
                            "access-key",
                            "secret-key"))
            .withBean(
                    AzureQueueProperties.class,
                    () -> new AzureQueueProperties(
                            "greenv-segment-extract-v2",
                            "UseDevelopmentStorage=true",
                            null,
                            false))
            .withPropertyValues(
                    "greenv.adapters.object-storage=s3",
                    "greenv.adapters.segment-queue=azure-queue");

    @Test
    void createsR2AndAzureQueueClientsTogetherWithoutANameCollision() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S3Client.class);
            assertThat(context).hasSingleBean(QueueClient.class);
            assertThat(context.getBean("azureSegmentQueueClient")).isInstanceOf(QueueClient.class);
        });
    }
}
