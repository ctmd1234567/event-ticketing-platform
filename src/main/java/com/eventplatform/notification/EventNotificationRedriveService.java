package com.eventplatform.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
public class EventNotificationRedriveService {
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final AmqpAdmin admin;
    private final DirectExchange exchange;
    private final Queue queue;
    private final Binding binding;
    private final TransactionTemplate transactions;
    private final long confirmTimeoutSeconds;

    public EventNotificationRedriveService(JdbcTemplate db, RabbitTemplate rabbit, AmqpAdmin admin,
            DirectExchange eventNotificationExchange, Queue eventNotificationQueue,
            Binding eventNotificationBinding, TransactionTemplate transactions,
            @Value("${app.event-notifications.confirm-timeout-seconds:5}") long confirmTimeoutSeconds) {
        this.db = db;
        this.rabbit = rabbit;
        this.admin = admin;
        this.exchange = eventNotificationExchange;
        this.queue = eventNotificationQueue;
        this.binding = eventNotificationBinding;
        this.transactions = transactions;
        this.confirmTimeoutSeconds = Math.max(1, confirmTimeoutSeconds);
    }

    public RedriveResult redrive(String eventId, long actorId, String reason) {
        if (!eventId.matches("ORDER_(PAID|CLOSED):[0-9]+")) {
            throw new IllegalArgumentException("Invalid Event notification ID");
        }
        Claim claim = transactions.execute(status -> claim(eventId, actorId, reason));
        if (claim == null) throw new IllegalStateException("Unable to claim Event notification");
        if (claim.action().equals("REACTIVATE_PUBLISH")) {
            return new RedriveResult(claim.operationId(), eventId, "PENDING");
        }
        try {
            admin.declareExchange(exchange);
            admin.declareQueue(queue);
            admin.declareBinding(binding);
            admin.declareQueue(new Queue(EventNotificationQueueConfig.DLQ, true));
            admin.declareBinding(new Binding(EventNotificationQueueConfig.DLQ,
                    Binding.DestinationType.QUEUE, EventNotificationQueueConfig.EXCHANGE,
                    "dead", null));
            // Reuse the original body/eventId. A crash between broker confirmation and
            // this local update can duplicate delivery; et_notification.event_id deduplicates.
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            rabbit.convertAndSend(EventNotificationQueueConfig.EXCHANGE,
                    EventNotificationQueueConfig.ROUTING_KEY, eventId, message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(eventId);
                        return message;
                    }, correlation);
            var confirm = correlation.getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("Redrive route was not confirmed");
            }
            transactions.executeWithoutResult(status -> {
                db.update("UPDATE et_notification_failure SET status='REDRIVEN',lease_until=NULL WHERE event_id=? AND status='REDRIVING'",
                        eventId);
                db.update("UPDATE et_notification_redrive SET outcome='CONFIRMED' WHERE id=?", claim.operationId());
            });
            return new RedriveResult(claim.operationId(), eventId, "CONFIRMED");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            String detail = failure.getClass().getSimpleName();
            transactions.executeWithoutResult(status -> {
                db.update("UPDATE et_notification_failure SET status='FAILED',lease_until=NULL WHERE event_id=? AND status='REDRIVING'",
                        eventId);
                db.update("UPDATE et_notification_redrive SET outcome='UNCERTAIN',detail=? WHERE id=?",
                        detail, claim.operationId());
            });
            throw new IllegalStateException("Redrive result uncertain; inspect notification and broker before retry", failure);
        }
    }

    private Claim claim(String eventId, long actorId, String reason) {
        List<String> statuses = db.queryForList("SELECT publish_status FROM et_outbox_event WHERE event_id=?",
                String.class, eventId);
        if (statuses.size() != 1) throw new IllegalArgumentException("Original outbox event does not exist");
        Integer delivered = db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, eventId);
        if (delivered != null && delivered > 0) throw new IllegalStateException("Notification already exists");
        long operationId = IdWorker.getId();
        int reactivated = db.update("""
                UPDATE et_outbox_event SET publish_status='PENDING',attempts=0,
                    next_attempt_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND publish_status='MANUAL_REQUIRED'
                """, eventId);
        if (reactivated == 1) {
            db.update("""
                    INSERT INTO et_notification_redrive(id,event_id,actor_id,reason,action,outcome)
                    VALUES (?,?,?,?,'REACTIVATE_PUBLISH','PENDING')
                    """, operationId, eventId, actorId, reason);
            return new Claim(operationId, "REACTIVATE_PUBLISH");
        }
        if (!statuses.getFirst().equals("PUBLISHED")) {
            throw new IllegalStateException("Publisher recovery owns this event");
        }
        db.update("""
                    INSERT IGNORE INTO et_notification_failure
                    (event_id,original_body,failure_kind,failure_reason,status)
                    VALUES (?,?,'MANUAL_GAP','Operator identified missing notification','FAILED')
                    """, eventId, eventId.getBytes(StandardCharsets.UTF_8));
        List<byte[]> bodies = db.query("""
                SELECT original_body FROM et_notification_failure WHERE event_id=?
                """, (rs, row) -> rs.getBytes(1), eventId);
        if (bodies.size() != 1 || !eventId.equals(new String(bodies.getFirst(), StandardCharsets.UTF_8))) {
            throw new IllegalStateException("No matching isolated original message");
        }
        int claimed = db.update("""
                UPDATE et_notification_failure SET status='REDRIVING',lease_until=?
                WHERE event_id=? AND (status='FAILED' OR (status='REDRIVING' AND lease_until<CURRENT_TIMESTAMP))
                """, Timestamp.from(Instant.now().plusSeconds(60)), eventId);
        if (claimed != 1) throw new IllegalStateException("Failure is already being redriven or completed");
        db.update("""
                UPDATE et_notification_redrive SET outcome='UNCERTAIN',detail='Redrive lease expired'
                WHERE event_id=? AND outcome='STARTED'
                """, eventId);
        db.update("""
                INSERT INTO et_notification_redrive(id,event_id,actor_id,reason,action,outcome)
                VALUES (?,?,?,?,'REDRIVE_CONSUMER','STARTED')
                """, operationId, eventId, actorId, reason);
        return new Claim(operationId, "REDRIVE_CONSUMER");
    }

    private record Claim(long operationId, String action) {}
    public record RedriveResult(long operationId, String eventId, String outcome) {}
}
