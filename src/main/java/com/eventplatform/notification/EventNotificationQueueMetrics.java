package com.eventplatform.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Samples ready messages without making a Prometheus scrape wait for RabbitMQ. */
@Component
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
public class EventNotificationQueueMetrics {
    private final RabbitTemplate rabbit;
    private final AtomicLong queueReady = new AtomicLong(-1);
    private final AtomicLong deadLetterReady = new AtomicLong(-1);
    private final AtomicLong sampledAtMillis = new AtomicLong(0);

    public EventNotificationQueueMetrics(RabbitTemplate rabbit, MeterRegistry registry) {
        this.rabbit = rabbit;
        Gauge.builder("event.notification.queue.ready", queueReady, value -> known(value.get(), sampledAtMillis.get()))
                .register(registry);
        Gauge.builder("event.notification.dlq.ready", deadLetterReady, value -> known(value.get(), sampledAtMillis.get()))
                .register(registry);
        Gauge.builder("event.notification.queue.sample.age.seconds", sampledAtMillis,
                value -> value.get() == 0 ? Double.NaN :
                        Math.max(0, System.currentTimeMillis() - value.get()) / 1000.0)
                .register(registry);
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 5000)
    public void sample() {
        try {
            long[] counts = rabbit.execute(channel -> new long[] {
                    channel.queueDeclarePassive(EventNotificationQueueConfig.QUEUE).getMessageCount(),
                    channel.queueDeclarePassive(EventNotificationQueueConfig.DLQ).getMessageCount()
            });
            if (counts == null) throw new IllegalStateException("RabbitMQ queue sample was empty");
            queueReady.set(counts[0]);
            deadLetterReady.set(counts[1]);
            sampledAtMillis.set(System.currentTimeMillis());
        } catch (RuntimeException unavailable) {
            queueReady.set(-1);
            deadLetterReady.set(-1);
        }
    }

    private static double known(long count, long sampledAt) {
        return count < 0 || sampledAt == 0 || System.currentTimeMillis() - sampledAt > 10_000
                ? Double.NaN : count;
    }
}
