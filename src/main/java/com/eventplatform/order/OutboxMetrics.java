package com.eventplatform.order;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@org.springframework.context.annotation.Profile("legacy-experiment")
@ConditionalOnProperty(name="app.outbox.enabled", havingValue="true")
public class OutboxMetrics {
    private final JdbcTemplate db;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();

    public OutboxMetrics(JdbcTemplate db, MeterRegistry registry) {
        this.db = db;
        registry.gauge("outbox.pending", pending);
        registry.gauge("outbox.oldest.age.seconds", oldestSeconds);
    }

    @Scheduled(initialDelayString="${app.metrics.outbox-initial-delay-ms:5000}",
            fixedDelayString="${app.metrics.outbox-interval-ms:5000}")
    public void refresh() {
        Map<String,Object> row = db.queryForMap("""
            SELECT COUNT(*) pending,
                   COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP),0) oldest_seconds
            FROM tb_outbox_event WHERE completed=FALSE
            """);
        pending.set(((Number) row.get("pending")).longValue());
        oldestSeconds.set(((Number) row.get("oldest_seconds")).longValue());
    }
}
