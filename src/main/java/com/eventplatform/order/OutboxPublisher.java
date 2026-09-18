package com.eventplatform.order;

import com.eventplatform.config.QueueConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
@org.springframework.context.annotation.Profile("legacy-experiment")
@ConditionalOnProperty(name="app.outbox.enabled", havingValue="true")
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final int batchSize;
    private final long leaseSeconds;
    private final long confirmTimeoutSeconds;

    public OutboxPublisher(
            JdbcTemplate db,
            RabbitTemplate rabbit,
            @Value("${app.outbox.batch-size:500}") int batchSize,
            @Value("${app.outbox.lease-seconds:60}") long leaseSeconds,
            @Value("${app.outbox.confirm-timeout-seconds:5}") long confirmTimeoutSeconds) {
        this.db = db;
        this.rabbit = rabbit;
        this.batchSize = Math.max(1, batchSize);
        this.leaseSeconds = Math.max(1, leaseSeconds);
        this.confirmTimeoutSeconds = Math.max(1, confirmTimeoutSeconds);
    }

    @Scheduled(fixedDelayString="${app.outbox.interval-ms:50}")
    public void publish() {
        // Reconcile until the consumer commits, even after broker confirmation. Duplicate delivery is safe.
        String leaseOwner = UUID.randomUUID().toString();
        Timestamp deadline = Timestamp.from(Instant.now().plusSeconds(leaseSeconds));
        String claimSql = "UPDATE tb_outbox_event SET lease_owner=?,next_attempt=?,attempts=attempts+1 "
                + "WHERE completed=FALSE AND next_attempt<=CURRENT_TIMESTAMP "
                + "ORDER BY next_attempt LIMIT " + batchSize;
        int claimed = db.update(claimSql, leaseOwner, deadline);
        if (claimed == 0) return;
        var ids = db.queryForList("SELECT id FROM tb_outbox_event WHERE lease_owner=? AND completed=FALSE ORDER BY id",
                Long.class, leaseOwner);
        List<PendingConfirm> pending = new ArrayList<>(ids.size());
        for (Long id : ids) {
            var correlation = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbit.convertAndSend(QueueConfig.EXCHANGE,QueueConfig.ROUTING_KEY,id.toString(),message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(id.toString());
                    return message;
                },correlation);
                pending.add(new PendingConfirm(id, correlation));
            } catch (Exception ex) {
                markFailure(id, ex);
            }
        }
        // Publish the entire leased batch before waiting, so broker confirms overlap instead of
        // imposing one network round-trip per event.
        List<Long> confirmed = new ArrayList<>(pending.size());
        for (PendingConfirm item : pending) {
            try {
                var confirm = item.correlation().getFuture().get(confirmTimeoutSeconds, TimeUnit.SECONDS);
                CorrelationData correlation = item.correlation();
                if (!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("Broker rejected or returned order event");
                confirmed.add(item.id());
            } catch (Exception ex) {
                markFailure(item.id(), ex);
            }
        }
        if (!confirmed.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(confirmed.size(), "?"));
            db.update("UPDATE tb_outbox_event SET last_error=NULL WHERE lease_owner=? AND id IN (" + placeholders + ")",
                    prepend(leaseOwner, confirmed));
        }
    }

    private Object[] prepend(String leaseOwner, List<Long> ids) {
        Object[] arguments = new Object[ids.size() + 1];
        arguments[0] = leaseOwner;
        for (int index = 0; index < ids.size(); index++) arguments[index + 1] = ids.get(index);
        return arguments;
    }

    private void markFailure(long id, Exception ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        db.update("UPDATE tb_outbox_event SET last_error='Publish failed; scheduled retry',lease_owner=NULL WHERE id=?",id);
        log.warn("Order event {} pending; automatic retry in {} seconds ({})",id,leaseSeconds,ex.getClass().getSimpleName());
    }

    private record PendingConfirm(long id, CorrelationData correlation) {}
}
