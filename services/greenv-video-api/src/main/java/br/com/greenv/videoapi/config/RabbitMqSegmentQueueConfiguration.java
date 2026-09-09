package br.com.greenv.videoapi.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqSegmentQueueConfiguration {

    @Bean
    DirectExchange captureExchange(CaptureQueueProperties queueProperties) {
        return new DirectExchange(queueProperties.exchange(), true, false);
    }

    @Bean
    Queue segmentExtractionQueue(CaptureQueueProperties queueProperties) {
        return new Queue(queueProperties.queue(), true);
    }

    /**
     * Worker 2 publishes its result to the same exchange with its own routing key. Nothing was bound
     * to that key until this queue existed, so the broker dropped every announcement it made.
     */
    @Bean
    @ConditionalOnExpression("'${greenv.capture-queue.measured-queue:}' != ''")
    Queue segmentMeasuredQueue(CaptureQueueProperties queueProperties) {
        return new Queue(queueProperties.measuredQueue(), true);
    }

    @Bean
    @ConditionalOnExpression("'${greenv.capture-queue.measured-queue:}' != ''")
    Binding segmentMeasuredBinding(
            Queue segmentMeasuredQueue,
            DirectExchange captureExchange,
            CaptureQueueProperties queueProperties) {
        return BindingBuilder.bind(segmentMeasuredQueue)
                .to(captureExchange)
                .with(queueProperties.measuredRoutingKey());
    }

    @Bean
    Binding segmentExtractionBinding(
            Queue segmentExtractionQueue,
            DirectExchange captureExchange,
            CaptureQueueProperties queueProperties) {
        return BindingBuilder.bind(segmentExtractionQueue)
                .to(captureExchange)
                .with(queueProperties.routingKey());
    }
}
