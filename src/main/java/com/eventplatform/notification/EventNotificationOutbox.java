package com.eventplatform.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persists notification intent in the caller's order/payment transaction. */
@Component
public class EventNotificationOutbox {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public EventNotificationOutbox(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public void orderPaid(long orderId, long userId) {
        append("ORDER_PAID", orderId, userId, null);
    }

    public void orderClosed(long orderId, long userId, String reason) {
        append("ORDER_CLOSED", orderId, userId, reason);
    }

    private void append(String type, long orderId, long userId, String reason) {
        String payload;
        try {
            payload = json.writeValueAsString(new Payload(orderId, userId, reason));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize Event notification intent", failure);
        }
        db.update("""
                INSERT INTO et_outbox_event(event_id,event_type,aggregate_id,user_id,payload)
                VALUES (?,?,?,?,?)
                """, type + ":" + orderId, type, orderId, userId, payload);
    }

    public record Payload(long orderId, long userId, String reason) {}
}
