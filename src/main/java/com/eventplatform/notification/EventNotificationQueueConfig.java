package com.eventplatform.notification;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
public class EventNotificationQueueConfig {
    public static final String EXCHANGE = "event-trading.notifications.v1";
    public static final String QUEUE = "event-trading.notifications.v1";
    public static final String ROUTING_KEY = "notification";

    @Bean
    DirectExchange eventNotificationExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue eventNotificationQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding eventNotificationBinding(Queue eventNotificationQueue, DirectExchange eventNotificationExchange) {
        return BindingBuilder.bind(eventNotificationQueue).to(eventNotificationExchange).with(ROUTING_KEY);
    }
}
