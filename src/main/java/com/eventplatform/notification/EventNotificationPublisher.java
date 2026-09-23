package com.eventplatform.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
public class EventNotificationPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventNotificationPublisher.class);
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final AmqpAdmin admin;
    private final DirectExchange exchange;
    private final Queue queue;
    private final Binding binding;
    private final int batchSize;
    private final int maxAttempts;
    private final long leaseSeconds;
    private final long confirmTimeoutSeconds;

    public EventNotificationPublisher(JdbcTemplate db, RabbitTemplate rabbit, AmqpAdmin admin,
            DirectExchange eventNotificationExchange, Queue eventNotificationQueue,
            Binding eventNotificationBinding,
            @Value("${app.event-notifications.batch-size:50}") int batchSize,
            @Value("${app.event-notifications.max-attempts:8}") int maxAttempts,
            @Value("${app.event-notifications.lease-seconds:30}") long leaseSeconds,
            @Value("${app.event-notifications.confirm-timeout-seconds:5}") long confirmTimeoutSeconds) {
        this.db = db;
        this.rabbit = rabbit;
        this.admin = admin;
        this.exchange = eventNotificationExchange;
        this.queue = eventNotificationQueue;
        this.binding = eventNotificationBinding;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.confirmTimeoutSeconds = Math.max(1, confirmTimeoutSeconds);
    }

    public void publish() {
        db.update("""
                UPDATE et_outbox_event
                SET publish_status='MANUAL_REQUIRED',lease_token=NULL,lease_until=NULL,
                    last_error='PUBLISH_ATTEMPTS_EXHAUSTED'
                WHERE publish_status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP AND attempts>=?
                """, maxAttempts);
        boolean topologyReady = false;
        for (int i = 0; i < batchSize; i++) {
            // Claim just before sending: a slow confirm cannot expire leases for
            // other rows in this scan or consume their retry budgets.
            String token = UUID.randomUUID().toString();
            Timestamp leaseUntil = Timestamp.from(Instant.now().plusSeconds(
                    Math.max(leaseSeconds, confirmTimeoutSeconds + 5)));
            int claimed = db.update("""
                    UPDATE et_outbox_event
                    SET publish_status='PROCESSING',lease_token=?,lease_until=?,attempts=attempts+1
                    WHERE attempts<? AND (
                        (publish_status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP)
                        OR (publish_status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP))
                    ORDER BY next_attempt_at,event_id LIMIT 1
                    """, token, leaseUntil, maxAttempts);
            if (claimed == 0) return;
            String id = db.queryForObject("""
                    SELECT event_id FROM et_outbox_event
                    WHERE publish_status='PROCESSING' AND lease_token=?
                    """, String.class, token);
            try {
                if (!topologyReady) {
                    // ACK without Return alone cannot prove the expected binding exists.
                    admin.declareExchange(exchange);
                    admin.declareQueue(queue);
                    admin.declareBinding(binding);
                    topologyReady = true;
                }
                // The business event ID remains stable; Confirm correlation identifies
                // this particular send and must be fresh on every retry.
                CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend(EventNotificationQueueConfig.EXCHANGE,
                        EventNotificationQueueConfig.ROUTING_KEY, id, message -> {
                            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                            message.getMessageProperties().setMessageId(id);
                            return message;
                        }, correlation);
                var confirm = correlation.getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("Expected notification route was not confirmed");
                }
                db.update("""
                        UPDATE et_outbox_event SET publish_status='PUBLISHED',published_at=CURRENT_TIMESTAMP,
                            lease_token=NULL,lease_until=NULL,last_error=NULL
                        WHERE event_id=? AND publish_status='PROCESSING' AND lease_token=?
                        """, id, token);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                fail(id, token, failure);
                if (!topologyReady || failure instanceof InterruptedException) return;
            }
        }
    }

    private void fail(String id, String token, Exception failure) {
        List<Integer> matches = db.queryForList("""
                SELECT attempts FROM et_outbox_event
                WHERE event_id=? AND publish_status='PROCESSING' AND lease_token=?
                """, Integer.class, id, token);
        if (matches.isEmpty()) return;
        int attempts = matches.getFirst();
        long delay = Math.min(300L, 1L << Math.min(attempts, 8));
        String detail = failure.getClass().getSimpleName();
        db.update("""
                UPDATE et_outbox_event SET publish_status=?,next_attempt_at=?,
                    lease_token=NULL,lease_until=NULL,last_error=?
                WHERE event_id=? AND publish_status='PROCESSING' AND lease_token=?
                """, attempts >= maxAttempts ? "MANUAL_REQUIRED" : "PENDING",
                Timestamp.from(Instant.now().plusSeconds(delay)), detail, id, token);
        log.warn("Event notification {} publish attempt {} failed: {}", id, attempts, detail);
    }
}
