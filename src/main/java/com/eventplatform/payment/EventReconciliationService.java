package com.eventplatform.payment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Focused, bounded checks of committed Event state. Repair is limited to a missing late-refund intent. */
@Service
public class EventReconciliationService {
    private final JdbcTemplate db;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate writes;

    public EventReconciliationService(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        snapshot = new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        writes = new TransactionTemplate(manager);
    }

    public Map<String, Object> scan(int requestedLimit) {
        return scan(requestedLimit, 0, 0, 0, "", "");
    }

    /** Each cursor advances one finding group; each call has its own consistent snapshot. */
    public Map<String, Object> scan(int requestedLimit, long afterOrderId, long afterPaymentId,
            long afterUnknownId, String afterUnknownKind, String afterEventId) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        long orderCursor = Math.max(0, afterOrderId);
        long paymentCursor = Math.max(0, afterPaymentId);
        long unknownCursor = Math.max(0, afterUnknownId);
        String kindCursor = afterUnknownKind == null ? "" : afterUnknownKind;
        String eventCursor = afterEventId == null ? "" : afterEventId;
        return Objects.requireNonNull(snapshot.execute(tx -> {
            Timestamp checkedAt = db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("checkedAt", checkedAt);
            result.put("closedWithReservation", db.queryForList("""
                    SELECT o.id order_id,r.id reservation_id,r.ticket_tier_id,r.quantity
                    FROM et_order o JOIN et_inventory_reservation r ON r.order_id=o.id
                    WHERE o.status='CLOSED' AND r.status='RESERVED' AND o.id>?
                    ORDER BY o.id LIMIT %d
                    """.formatted(limit), orderCursor));
            result.put("paymentRefundConflict", db.queryForList("""
                    SELECT p.id payment_id,o.id order_id,o.status order_status,p.status payment_status,
                           r.id refund_id,r.status refund_status,r.reason refund_reason
                    FROM et_payment p JOIN et_order o ON o.id=p.order_id
                    LEFT JOIN et_refund r ON r.payment_id=p.id
                    WHERE p.id>? AND (
                          (p.status='SUCCEEDED' AND o.status='PENDING_PAYMENT')
                       OR (o.status IN ('PAID','FULFILLED','REFUNDING','REFUNDED') AND p.status<>'SUCCEEDED')
                       OR (p.status='SUCCEEDED' AND o.status='CLOSED' AND r.id IS NULL)
                       OR (o.status IN ('REFUNDING','REFUNDED') AND (r.id IS NULL OR r.reason<>'USER_REQUEST'))
                       OR (o.status='REFUNDED' AND r.status<>'SUCCEEDED')
                       OR (r.reason='USER_REQUEST' AND r.status='SUCCEEDED' AND o.status<>'REFUNDED')
                       OR (r.reason='LATE_PAYMENT' AND o.status<>'CLOSED')
                       OR (r.id IS NOT NULL AND p.status<>'SUCCEEDED')
                       OR (r.amount<>p.amount OR r.currency<>p.currency))
                    ORDER BY p.id LIMIT %d
                    """.formatted(limit), paymentCursor));
            result.put("longUnknown", db.queryForList("""
                    SELECT u.kind,u.id,u.status,u.recovery_status,u.updated_at FROM (
                        SELECT 'PAYMENT' kind,id,status,recovery_status,updated_at FROM et_payment
                        WHERE status='UNKNOWN' AND updated_at<?
                        UNION ALL
                        SELECT 'REFUND' kind,id,status,recovery_status,updated_at FROM et_refund
                        WHERE status='UNKNOWN' AND updated_at<?
                    ) u WHERE (u.id>? OR (u.id=? AND u.kind>?))
                    ORDER BY u.id,u.kind LIMIT %d
                    """.formatted(limit),
                    Timestamp.from(checkedAt.toInstant().minusSeconds(900)),
                    Timestamp.from(checkedAt.toInstant().minusSeconds(900)),
                    unknownCursor, unknownCursor, kindCursor));
            result.put("stalledOutbox", db.queryForList("""
                    SELECT e.event_id,e.publish_status,e.attempts,e.updated_at,e.last_error
                    FROM et_outbox_event e LEFT JOIN et_notification n ON n.event_id=e.event_id
                    WHERE e.event_id>? AND ((e.publish_status='MANUAL_REQUIRED')
                       OR (e.publish_status='PROCESSING' AND e.lease_until<?)
                       OR (e.publish_status='PENDING' AND e.next_attempt_at<?)
                       OR (e.publish_status='PUBLISHED' AND n.event_id IS NULL AND e.updated_at<?))
                    ORDER BY e.event_id LIMIT %d
                    """.formatted(limit), eventCursor, checkedAt,
                    Timestamp.from(checkedAt.toInstant().minusSeconds(600)),
                    Timestamp.from(checkedAt.toInstant().minusSeconds(600))));
            return result;
        }));
    }

    /** Recheck under the same order/payment lock order as the payment service. */
    public long repairMissingLateRefund(long paymentId, long actorId) {
        return Objects.requireNonNull(writes.execute(tx -> {
            var orderIds = db.queryForList("SELECT order_id FROM et_payment WHERE id=?", Long.class, paymentId);
            if (orderIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
            String orderStatus = db.queryForObject("SELECT status FROM et_order WHERE id=? FOR UPDATE",
                    String.class, orderIds.getFirst());
            Map<String, Object> payment = db.queryForMap("""
                    SELECT status,amount,currency,recovery_status FROM et_payment WHERE id=? FOR UPDATE
                    """, paymentId);
            if (!"CLOSED".equals(orderStatus) || !"SUCCEEDED".equals(payment.get("status"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Late refund preconditions no longer hold");
            }
            String reservationStatus = db.queryForObject("""
                    SELECT status FROM et_inventory_reservation WHERE order_id=? FOR UPDATE
                    """, String.class, orderIds.getFirst());
            if (!"RELEASED".equals(reservationStatus)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Closed order has unreleased inventory");
            }
            var existing = db.queryForList("SELECT id FROM et_refund WHERE payment_id=?", Long.class, paymentId);
            if (!existing.isEmpty()) {
                String reason = db.queryForObject("SELECT reason FROM et_refund WHERE id=?",
                        String.class, existing.getFirst());
                if (!"LATE_PAYMENT".equals(reason)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment has a different refund");
                }
                return existing.getFirst();
            }
            long refundId = IdWorker.getId();
            int inserted = db.update("""
                    INSERT INTO et_refund(id,refund_number,payment_id,reason,amount,currency,status,
                        recovery_status,attempts,next_attempt_at)
                    SELECT ?,?,p.id,'LATE_PAYMENT',p.amount,p.currency,'REQUESTED','AUTO',0,CURRENT_TIMESTAMP
                    FROM et_payment p JOIN et_order o ON o.id=p.order_id
                    JOIN et_inventory_reservation v ON v.order_id=o.id
                    WHERE p.id=? AND p.status='SUCCEEDED' AND o.status='CLOSED'
                      AND v.status='RELEASED'
                      AND NOT EXISTS (SELECT 1 FROM et_refund r WHERE r.payment_id=p.id)
                    """, refundId, "ER" + refundId, paymentId);
            if (inserted != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Late refund state changed");
            db.update("""
                    INSERT INTO et_payment_history(id,payment_id,refund_id,action,from_status,to_status,
                        source,actor_id,detail)
                    VALUES (?,?,?,'RECONCILE_LATE_REFUND',NULL,'REQUESTED','REFUND_SERVICE',?,'MISSING_LATE_REFUND')
                    """, IdWorker.getId(), paymentId, refundId, actorId);
            if (!"NONE".equals(payment.get("recovery_status"))) {
                db.update("""
                        UPDATE et_payment SET recovery_status='NONE',next_attempt_at=NULL,
                            lease_token=NULL,lease_until=NULL,last_error=NULL,version=version+1
                        WHERE id=? AND status='SUCCEEDED'
                        """, paymentId);
            }
            return refundId;
        }));
    }
}
