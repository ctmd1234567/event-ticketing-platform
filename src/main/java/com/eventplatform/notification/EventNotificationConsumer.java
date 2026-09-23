package com.eventplatform.notification;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.event-notifications.enabled", havingValue = "true")
public class EventNotificationConsumer {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public EventNotificationConsumer(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @RabbitListener(queues = EventNotificationQueueConfig.QUEUE)
    @Transactional
    public void receive(String eventId) throws Exception {
        var event = db.query("""
                SELECT event_type,aggregate_id,user_id,payload FROM et_outbox_event WHERE event_id=?
                """, (rs, row) -> new EventRow(rs.getString("event_type"), rs.getLong("aggregate_id"),
                        rs.getLong("user_id"), rs.getString("payload")), eventId);
        if (event.size() != 1) throw new IllegalArgumentException("Unknown Event notification " + eventId);
        EventRow row = event.getFirst();
        EventNotificationOutbox.Payload payload = json.readValue(row.payload(), EventNotificationOutbox.Payload.class);
        if (!eventId.equals(row.type() + ":" + row.orderId())
                || payload.orderId() != row.orderId() || payload.userId() != row.userId()) {
            throw new IllegalArgumentException("Invalid Event notification payload " + eventId);
        }
        String content = switch (row.type()) {
            case "ORDER_PAID" -> "Order payment succeeded";
            case "ORDER_CLOSED" -> "Order closed: " + payload.reason();
            default -> throw new IllegalArgumentException("Unsupported Event notification type " + row.type());
        };
        try {
            db.update("""
                    INSERT INTO et_notification(id,event_id,user_id,order_id,notification_type,content)
                    VALUES (?,?,?,?,?,?)
                    """, IdWorker.getId(), eventId, row.userId(), row.orderId(), row.type(), content);
        } catch (DuplicateKeyException duplicate) {
            Integer count = db.queryForObject("""
                    SELECT COUNT(*) FROM et_notification WHERE event_id=? AND user_id=? AND order_id=?
                    """, Integer.class, eventId, row.userId(), row.orderId());
            if (count == null || count != 1) throw duplicate;
        }
        // AUTO acknowledgement is sent after this transactional method commits.
    }

    private record EventRow(String type, long orderId, long userId, String payload) {}
}
