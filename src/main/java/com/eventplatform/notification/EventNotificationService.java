package com.eventplatform.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class EventNotificationService {
    private final JdbcTemplate db;

    public EventNotificationService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(long userId) {
        return db.query("""
                SELECT id,event_id,order_id,notification_type,content,created_at
                FROM et_notification WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 100
                """, (rs, row) -> new NotificationView(rs.getLong("id"), rs.getString("event_id"),
                        rs.getLong("order_id"), rs.getString("notification_type"),
                        rs.getString("content"), rs.getTimestamp("created_at").toInstant()), userId);
    }

    public record NotificationView(long id, String eventId, long orderId, String type,
            String content, Instant createdAt) {}
}
