package br.com.greenv.videoapi.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class CaptureQueueConfiguration {

    @Bean
    DirectExchange captureExchange(CaptureQueueProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    Queue segmentExtractionQueue(CaptureQueueProperties properties) {
        return new Queue(properties.queue(), true);
    }

    @Bean
    Binding segmentExtractionBinding(
            Queue segmentExtractionQueue,
            DirectExchange captureExchange,
            CaptureQueueProperties properties) {
        return BindingBuilder.bind(segmentExtractionQueue)
                .to(captureExchange)
                .with(properties.routingKey());
    }
}
