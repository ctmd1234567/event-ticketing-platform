package com.eventplatform.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Database-backed Event gauges. A negative value means the last refresh failed. */
@Component
public class EventOperationsMetrics {
    private static final Logger log = LoggerFactory.getLogger(EventOperationsMetrics.class);
    private final JdbcTemplate db;
    private final AtomicLong persistedOrders = new AtomicLong(-1);
    private final AtomicLong pendingOutbox = new AtomicLong(-1);
    private final AtomicLong oldestOutboxSeconds = new AtomicLong(-1);

    public EventOperationsMetrics(JdbcTemplate db, MeterRegistry registry) {
        this.db = db;
        registry.gauge("event.orders.persisted", persistedOrders);
        registry.gauge("event.notifications.outbox.pending", pendingOutbox);
        registry.gauge("event.notifications.outbox.oldest.seconds", oldestOutboxSeconds);
    }

    @Scheduled(initialDelayString = "${app.metrics.event-initial-delay-ms:5000}",
            fixedDelayString = "${app.metrics.event-interval-ms:30000}")
    public void refresh() {
        try {
            Long orders = db.queryForObject("SELECT COUNT(*) FROM et_order", Long.class);
            Map<String, Object> outbox = db.queryForMap("""
                    SELECT COUNT(*) AS pending,
                           COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP),0)
                             AS oldest_seconds
                    FROM et_outbox_event
                    WHERE publish_status IN ('PENDING','PROCESSING','MANUAL_REQUIRED')
                    """);
            persistedOrders.set(orders == null ? -1 : orders);
            pendingOutbox.set(((Number) outbox.get("pending")).longValue());
            oldestOutboxSeconds.set(((Number) outbox.get("oldest_seconds")).longValue());
        } catch (RuntimeException failure) {
            persistedOrders.set(-1);
            pendingOutbox.set(-1);
            oldestOutboxSeconds.set(-1);
            log.warn("Could not refresh Event operational gauges", failure);
        }
    }
}
