package com.eventplatform.notification;

import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class EventNotificationFailureRecorder {
    private final JdbcTemplate db;

    public EventNotificationFailureRecorder(JdbcTemplate db) {
        this.db = db;
    }

    public void record(Message message, Throwable cause) {
        String eventId = new String(message.getBody(), StandardCharsets.UTF_8);
        if (!eventId.matches("ORDER_(PAID|CLOSED):[0-9]+") || eventId.length() > 64) {
            // Keep malformed and oversized bodies distinct without storing arbitrary UTF-8
            // in the ASCII event_id column. The original bytes remain in original_body.
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(message.getBody());
                eventId = "INVALID_SHA256:" + HexFormat.of().formatHex(hash).substring(0, 48);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
        Throwable root = cause;
        while (root.getCause() != null) root = root.getCause();
        String kind = root instanceof IllegalArgumentException
                || root instanceof com.fasterxml.jackson.core.JsonProcessingException
                ? "UNPROCESSABLE" : "TECHNICAL";
        String detail = root.getClass().getSimpleName() + ": " + root.getMessage();
        if (detail.length() > 255) detail = detail.substring(0, 255);
        db.update("""
                INSERT INTO et_notification_failure(event_id,original_body,failure_kind,failure_reason,status)
                VALUES (?,?,?,?,'FAILED')
                ON DUPLICATE KEY UPDATE original_body=VALUES(original_body),
                    failure_kind=VALUES(failure_kind),failure_reason=VALUES(failure_reason),
                    status='FAILED',failure_count=failure_count+1
                """, eventId, message.getBody(), kind, detail);
    }
}
