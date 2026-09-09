package br.com.greenv.frameextractor.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class CloudClientConfiguration {

    @Bean
    @ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "s3")
    S3Client s3Client(S3StorageProperties properties) {
        requireText(properties.bucket(), "greenv.storage.s3.bucket");
        var builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(valueOrDefault(properties.region(), "us-east-1")))
                .credentialsProvider(credentials(properties.accessKey(), properties.secretKey()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyleAccess())
                        .build());
        if (hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "sqs")
    SqsClient sqsClient(SqsQueueProperties properties) {
        requireText(properties.queueUrl(), "greenv.queue.sqs.queue-url");
        var builder = SqsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(valueOrDefault(properties.region(), "us-east-1")))
                .credentialsProvider(credentials(properties.accessKey(), properties.secretKey()));
        if (hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "azure-blob")
    BlobContainerClient blobContainerClient(AzureBlobStorageProperties properties) {
        requireText(properties.container(), "greenv.storage.azure-blob.container");
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (hasText(properties.connectionString())) {
            builder.connectionString(properties.connectionString());
        } else {
            builder.endpoint(requireText(properties.endpoint(), "greenv.storage.azure-blob.endpoint"))
                    .credential(new DefaultAzureCredentialBuilder().build());
        }
        BlobContainerClient client = builder.buildClient().getBlobContainerClient(properties.container());
        if (properties.createContainer()) {
            client.createIfNotExists();
        }
        return client;
    }

    @Bean("azureSegmentQueueClient")
    @ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
    QueueClient azureQueueClient(AzureQueueProperties properties) {
        requireText(properties.queue(), "greenv.queue.azure-queue.queue");
        return azureQueueClient(properties, properties.queue());
    }

    @Bean("azureSegmentPoisonQueueClient")
    @ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
    QueueClient azurePoisonQueueClient(AzureQueueProperties properties) {
        return azureQueueClient(
                properties,
                requireText(properties.poisonQueue(), "greenv.queue.azure-queue.poison-queue"));
    }

    /**
     * The queue worker 2 drains. Built only when this deployment both speaks Azure Queue and runs a
     * measurement worker, so a stack without one neither names a queue nor holds a client to it.
     */
    @Bean("azureMeasurementQueueClient")
    @ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-queue")
    @ConditionalOnProperty(name = "greenv.measurement.enabled", havingValue = "true")
    QueueClient azureMeasurementQueueClient(AzureQueueProperties properties) {
        return azureQueueClient(
                properties,
                requireText(properties.measurementQueue(), "greenv.queue.azure-queue.measurement-queue"));
    }

    private QueueClient azureQueueClient(AzureQueueProperties properties, String queueName) {
        QueueClientBuilder builder = new QueueClientBuilder().queueName(queueName);
        if (hasText(properties.connectionString())) {
            builder.connectionString(properties.connectionString());
        } else {
            builder.endpoint(requireText(properties.endpoint(), "greenv.queue.azure-queue.endpoint"))
                    .credential(new DefaultAzureCredentialBuilder().build());
        }
        QueueClient client = builder.buildClient();
        if (properties.createQueue()) {
            client.createIfNotExists();
        }
        return client;
    }

    @Bean
    @ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-service-bus")
    ServiceBusSenderClient serviceBusSenderClient(AzureServiceBusProperties properties) {
        requireText(properties.queue(), "greenv.queue.azure-service-bus.queue");
        return serviceBusBuilder(properties).sender().queueName(properties.queue()).buildClient();
    }

    @Bean
    @ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "azure-service-bus")
    ServiceBusReceiverClient serviceBusReceiverClient(AzureServiceBusProperties properties) {
        requireText(properties.queue(), "greenv.queue.azure-service-bus.queue");
        return serviceBusBuilder(properties)
                .receiver()
                .queueName(properties.queue())
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
    }

    private static ServiceBusClientBuilder serviceBusBuilder(AzureServiceBusProperties properties) {
        ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (hasText(properties.connectionString())) {
            return builder.connectionString(properties.connectionString());
        }
        return builder.credential(
                requireText(
                        properties.fullyQualifiedNamespace(),
                        "greenv.queue.azure-service-bus.fully-qualified-namespace"),
                new DefaultAzureCredentialBuilder().build());
    }

    private static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
        if (!hasText(accessKey) && !hasText(secretKey)) {
            return DefaultCredentialsProvider.builder().build();
        }
        requireText(accessKey, "AWS access key");
        requireText(secretKey, "AWS secret key");
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return hasText(value) ? value : defaultValue;
    }

    private static String requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required by the selected adapter");
        }
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
