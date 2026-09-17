package com.eventplatform.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentRecoveryAdminService {
    private final JdbcTemplate db;

    public PaymentRecoveryAdminService(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> items(int requestedLimit, long afterId) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        long cursor = Math.max(0, afterId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payments", db.queryForList("""
                SELECT id,payment_number,order_id,status,recovery_status,attempts,last_error,updated_at
                FROM et_payment WHERE recovery_status='MANUAL_REQUIRED' AND id>? ORDER BY id LIMIT %d
                """.formatted(limit), cursor));
        result.put("refunds", db.queryForList("""
                SELECT id,refund_number,payment_id,status,recovery_status,attempts,last_error,updated_at
                FROM et_refund WHERE recovery_status='MANUAL_REQUIRED' AND id>? ORDER BY id LIMIT %d
                """.formatted(limit), cursor));
        result.put("callbacks", db.queryForList("""
                SELECT id,event_id,kind,business_number,status,recovery_status,reason,last_error,updated_at
                FROM et_payment_callback
                WHERE id>? AND (status IN ('UNMATCHED','REJECTED') OR recovery_status='MANUAL_REQUIRED')
                ORDER BY id LIMIT %d
                """.formatted(limit), cursor));
        return result;
    }
}
