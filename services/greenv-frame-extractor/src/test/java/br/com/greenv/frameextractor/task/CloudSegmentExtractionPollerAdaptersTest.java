package br.com.greenv.frameextractor.task;

import static br.com.greenv.frameextractor.task.CloudSegmentWorkQueueAdaptersTest.codec;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.greenv.frameextractor.config.AzureQueueProperties;
import br.com.greenv.frameextractor.config.AzureServiceBusProperties;
import br.com.greenv.frameextractor.config.SqsQueueProperties;
import br.com.greenv.frameextractor.port.SegmentExtractionUseCase;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.service.ExtractionException;
import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.models.QueueMessageItem;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

class CloudSegmentExtractionPollerAdaptersTest {

    @Test
    void sqsDeletesOnlySuccessfullyHandledMessages() {
        SqsClient client = mock(SqsClient.class);
        SegmentExtractionUseCase useCase = mock(SegmentExtractionUseCase.class);
        SqsSegmentExtractionPollerAdapter poller = new SqsSegmentExtractionPollerAdapter(
                client,
                codec(),
                useCase,
                new SqsQueueProperties("queue", "us-east-1", "", "", "", "", 1, 10, 300));
        Message message = Message.builder().messageId("message-1").receiptHandle("receipt").body("{}").build();

        poller.process(message);

        verify(useCase).handle(any());
        verify(client).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void sqsLeavesFailedMessagesForProviderRedrive() {
        SqsClient client = mock(SqsClient.class);
        SegmentMessageCodec codec = mock(SegmentMessageCodec.class);
        when(codec.decode(any())).thenThrow(new ExtractionException("invalid", "invalid", false));
        SqsSegmentExtractionPollerAdapter poller = new SqsSegmentExtractionPollerAdapter(
                client,
                codec,
                mock(SegmentExtractionUseCase.class),
                new SqsQueueProperties("queue", "us-east-1", "", "", "", "", 1, 10, 300));

        poller.process(Message.builder().messageId("message-1").receiptHandle("receipt").body("{}").build());

        verify(client, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void azureQueueDeletesOnlySuccessfullyHandledMessages() {
        QueueClient client = mock(QueueClient.class);
        QueueClient poisonQueue = mock(QueueClient.class);
        QueueMessageItem message = mock(QueueMessageItem.class);
        when(message.getBody()).thenReturn(BinaryData.fromString("{}"));
        when(message.getMessageId()).thenReturn("message-1");
        when(message.getPopReceipt()).thenReturn("receipt");
        SegmentExtractionUseCase useCase = mock(SegmentExtractionUseCase.class);
        var poller = new AzureQueueSegmentExtractionPollerAdapter(
                client,
                poisonQueue,
                codec(),
                useCase,
                new AzureQueueProperties("segments", "connection", "", false, 1, 300, "poison", 5, "measure"));

        poller.process(message);

        verify(useCase).handle(any());
        verify(client).deleteMessage("message-1", "receipt");
    }

    @Test
    void azureQueueMovesExhaustedMessagesToThePoisonQueue() {
        QueueClient client = mock(QueueClient.class);
        QueueClient poisonQueue = mock(QueueClient.class);
        QueueMessageItem message = mock(QueueMessageItem.class);
        when(message.getBody()).thenReturn(BinaryData.fromString("invalid"));
        when(message.getMessageId()).thenReturn("message-1");
        when(message.getPopReceipt()).thenReturn("receipt");
        when(message.getDequeueCount()).thenReturn(5L);
        SegmentMessageCodec codec = mock(SegmentMessageCodec.class);
        when(codec.decode("invalid")).thenThrow(new ExtractionException("invalid", "invalid", false));
        var poller = new AzureQueueSegmentExtractionPollerAdapter(
                client,
                poisonQueue,
                codec,
                mock(SegmentExtractionUseCase.class),
                new AzureQueueProperties("segments", "connection", "", false, 1, 300, "poison", 5, "measure"));

        poller.process(message);

        verify(poisonQueue).sendMessage("invalid");
        verify(client).deleteMessage("message-1", "receipt");
    }

    @Test
    void serviceBusCompletesSuccessAndAbandonsFailure() {
        ServiceBusReceiverClient client = mock(ServiceBusReceiverClient.class);
        ServiceBusReceivedMessage success = message("success");
        SegmentMessageCodec codec = codec();
        var poller = new AzureServiceBusSegmentExtractionPollerAdapter(
                client,
                codec,
                mock(SegmentExtractionUseCase.class),
                new AzureServiceBusProperties("segments", "connection", "", 1, 10));

        poller.process(success);
        verify(client).complete(success);

        ServiceBusReceivedMessage failure = message("failure");
        when(codec.decode("failure")).thenThrow(new ExtractionException("invalid", "invalid", false));
        poller.process(failure);
        verify(client).abandon(failure);
        verify(client, never()).complete(failure);
    }

    private static ServiceBusReceivedMessage message(String body) {
        ServiceBusReceivedMessage message = mock(ServiceBusReceivedMessage.class);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getMessageId()).thenReturn(body);
        return message;
    }
}
