package com.eventplatform.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.function.ToDoubleFunction;

/** Database-backed gauges for durable Event work and its oldest pending intent. */
@Component
public class EventOperationalMetrics {
    public EventOperationalMetrics(JdbcTemplate db, MeterRegistry registry) {
        gauge(registry, "event.orders.persisted", db,
                "SELECT COUNT(*) FROM et_order");
        gauge(registry, "event.outbox.backlog", db,
                "SELECT COUNT(*) FROM et_outbox_event WHERE publish_status IN ('PENDING','PROCESSING')");
        gauge(registry, "event.outbox.oldest.pending.seconds", db,
                "SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(created_at),CURRENT_TIMESTAMP),0) " +
                        "FROM et_outbox_event WHERE publish_status IN ('PENDING','PROCESSING')");
        gauge(registry, "event.outbox.manual.required", db,
                "SELECT COUNT(*) FROM et_outbox_event WHERE publish_status='MANUAL_REQUIRED'");
    }

    private void gauge(MeterRegistry registry, String name, JdbcTemplate db, String query) {
        Gauge.builder(name, db, safeQuery(query)).register(registry);
    }

    private ToDoubleFunction<JdbcTemplate> safeQuery(String query) {
        return db -> {
            try {
                Long value = db.queryForObject(query, Long.class);
                return value == null ? Double.NaN : value.doubleValue();
            } catch (DataAccessException unavailable) {
                return Double.NaN;
            }
        };
    }
}
