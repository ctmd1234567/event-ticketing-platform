package com.eventplatform.notification;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

@Configuration
@EnableRabbit
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
public class EventNotificationQueueConfig {
    public static final String EXCHANGE = "event-trading.notifications.v2";
    public static final String QUEUE = "event-trading.notifications.v2";
    public static final String DLQ = "event-trading.notifications.dlq.v2";
    public static final String ROUTING_KEY = "notification";

    @Bean
    DirectExchange eventNotificationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue eventNotificationQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey("dead").build();
    }

    @Bean
    Queue eventNotificationDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding eventNotificationDeadLetterBinding(Queue eventNotificationDeadLetterQueue,
            DirectExchange eventNotificationExchange) {
        return BindingBuilder.bind(eventNotificationDeadLetterQueue)
                .to(eventNotificationExchange).with("dead");
    }

    @Bean
    Binding eventNotificationBinding(Queue eventNotificationQueue, DirectExchange eventNotificationExchange) {
        return BindingBuilder.bind(eventNotificationQueue).to(eventNotificationExchange).with(ROUTING_KEY);
    }

    @Bean
    SimpleRabbitListenerContainerFactory eventNotificationListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            EventNotificationFailureRecorder failures) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(new SimpleRetryPolicy(4, Map.of(
                        IllegalArgumentException.class, false,
                        com.fasterxml.jackson.core.JsonProcessingException.class, false), true, true))
                .backOffOptions(1000, 2.0, 4000)
                .recoverer((message, cause) -> {
                    failures.record(message, cause);
                    throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                            "Event notification moved to DLQ after bounded attempts", cause);
                }).build());
        return factory;
    }
}
