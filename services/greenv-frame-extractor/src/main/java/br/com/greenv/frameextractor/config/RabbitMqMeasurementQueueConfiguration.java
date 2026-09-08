package br.com.greenv.frameextractor.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The measurement queue, bound to the exchange extraction already declares.
 *
 * Declared here rather than by the measurement worker because queues are infrastructure: the
 * worker is written in another language and runs on its own schedule, and a consumer that
 * declares its own topology is a consumer that can silently create the wrong one.
 */
@Configuration
// Both conditions, not just the first: `captureExchange` is declared by the segment-queue
// configuration, so binding to it while that configuration is switched off asks for a bean
// nobody defined and the service fails to start.
@ConditionalOnProperty(name = "greenv.measurement.enabled", havingValue = "true")
@ConditionalOnProperty(name = "greenv.adapters.segment-queue", havingValue = "rabbitmq", matchIfMissing = true)
public class RabbitMqMeasurementQueueConfiguration {

    @Bean
    Queue segmentMeasurementQueue(MeasurementQueueProperties measurementProperties) {
        return new Queue(measurementProperties.queue(), true);
    }

    @Bean
    Binding segmentMeasurementBinding(
            Queue segmentMeasurementQueue,
            DirectExchange captureExchange,
            MeasurementQueueProperties measurementProperties) {
        return BindingBuilder.bind(segmentMeasurementQueue)
                .to(captureExchange)
                .with(measurementProperties.routingKey());
    }
}
