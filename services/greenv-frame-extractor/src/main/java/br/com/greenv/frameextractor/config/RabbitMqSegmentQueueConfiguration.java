package br.com.greenv.frameextractor.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
