package com.eventplatform.payment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.eventplatform.notification.EventNotificationOutbox;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Local payment orchestration. Provider calls always happen outside local business transactions. */
@Service
public class EventPaymentService {
    private final JdbcTemplate db;
    private final EventNotificationOutbox notificationOutbox;
    private final TransactionTemplate transactions;
    private final SimulatedPaymentGateway gateway;

    public EventPaymentService(JdbcTemplate db, PlatformTransactionManager manager,
            SimulatedPaymentGateway gateway, EventNotificationOutbox notificationOutbox) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
        this.gateway = gateway;
        this.notificationOutbox = notificationOutbox;
    }

    public PaymentView create(long orderId, long userId, String key) {
        requireNoTransaction();
        validateKey(key);
        Prepared prepared;
        try {
            prepared = required(transactions.execute(tx -> prepare(orderId, userId, key)));
        } catch (DuplicateKeyException race) {
            // The failed transaction has rolled back, including its history and intent.
            var matches = db.query("SELECT * FROM et_payment WHERE user_id=? AND idempotency_key=?",
                    (rs, row) -> map(rs), userId, key);
            if (matches.isEmpty()) throw race;
            return replay(matches.getFirst(), orderId, key);
        }
        if (!prepared.created()) return prepared.payment();
        PaymentView payment = prepared.payment();
        SimulatedPaymentGateway.GatewayPayment result;
        try {
            // Preparation has committed; no local locks/transaction surround the provider call.
            result = gateway.createPayment(new SimulatedPaymentGateway.PaymentRequest(
                    payment.paymentNumber(), prepared.orderNumber(), payment.amount(), payment.currency()));
        } catch (RuntimeException transportOrProviderError) {
            // An exception is not trusted evidence of provider failure. Keep the original ID.
            markUnknown(payment.id(), userId, transportOrProviderError.getClass().getSimpleName());
            return payment(payment.id(), userId);
        }
        // Do not catch local persistence/validation failures as transport failures. The committed
        // PROCESSING intent survives, while every partial local result change rolls back.
        return applyResult(payment.id(), result);
    }

    public PaymentView payment(long paymentId, long userId) {
        try {
            return db.queryForObject("SELECT * FROM et_payment WHERE id=? AND user_id=?",
                    (rs, row) -> map(rs), paymentId, userId);
        } catch (EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
    }

    PaymentView paymentByNumber(String paymentNumber) {
        try {
            return db.queryForObject("SELECT * FROM et_payment WHERE payment_number=?", (rs, row) -> map(rs), paymentNumber);
        } catch (EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
    }

    private Prepared prepare(long orderId, long userId, String key) {
        LockedOrder order = lockOrder(orderId);
        if (order.userId() != userId) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        var existing = db.query("SELECT * FROM et_payment WHERE order_id=? FOR UPDATE",
                (rs, row) -> map(rs), orderId);
        if (!existing.isEmpty()) return new Prepared(replay(existing.getFirst(), orderId, key), order.number(), false);
        var reusedKey = db.queryForList("SELECT id FROM et_payment WHERE user_id=? AND idempotency_key=?",
                Long.class, userId, key);
        if (!reusedKey.isEmpty()) throw conflict("Payment idempotency key already used for another order");
        if (!"PENDING_PAYMENT".equals(order.status())) throw conflict("Order is not pending payment");
        if (!now().isBefore(order.deadline())) throw conflict("Order payment deadline has passed");
        long id = IdWorker.getId();
        // Initial intent and dispatch claim share one short transaction. A crash leaves an
        // observable PROCESSING record; replays never dispatch a second command in this slice.
        db.update("""
                INSERT INTO et_payment(id,payment_number,order_id,user_id,provider,idempotency_key,
                    request_hash,amount,currency,status,attempts,recovery_status,next_attempt_at)
                VALUES (?,?,?,?,'simulated',?,?,?,?,'PROCESSING',1,'AUTO',?)
                """, id, "EP" + id, orderId, userId, key, hash(orderId), order.amount(), order.currency(),
                Timestamp.from(now().plusSeconds(30)));
        history(id, null, "PROCESSING", "PAYMENT_STARTED", null, null);
        return new Prepared(payment(id, userId), order.number(), true);
    }

    // Package-private: only trusted gateway results may reach this seam. It is not a callback API.
    PaymentView applyResult(long paymentId, SimulatedPaymentGateway.GatewayPayment result) {
        return applyResult(paymentId, result, null);
    }

    PaymentView applyCallbackResult(long paymentId, SimulatedPaymentGateway.GatewayPayment result, long callbackId) {
        return applyResult(paymentId, result, callbackId);
    }

    private PaymentView applyResult(long paymentId, SimulatedPaymentGateway.GatewayPayment result, Long callbackId) {
        requireNoTransaction();
        long orderId = required(db.queryForObject("SELECT order_id FROM et_payment WHERE id=?", Long.class, paymentId));
        return required(transactions.execute(tx -> {
            LockedOrder order = lockOrder(orderId);
            PaymentView current = lockPayment(paymentId);
            boolean applyCallback = callbackId == null || lockCallbackForApply(callbackId);
            validateResult(order, current, result);
            if (!applyCallback) return current;
            if ("SUCCEEDED".equals(current.status())) {
                if (result.status() != GatewayResultStatus.SUCCEEDED) {
                    throw conflict("Contradictory result for a successful payment");
                }
                if ("CLOSED".equals(order.status())) ensureCompensationIntent(current);
                if (callbackId != null) acknowledgeTerminalCallback(paymentId, current.status(), callbackId);
                return payment(paymentId, current.userId());
            }
            if (!"PROCESSING".equals(current.status()) && !"UNKNOWN".equals(current.status())) {
                if (current.status().equals(result.status().name())) {
                    if (callbackId != null) acknowledgeTerminalCallback(paymentId, current.status(), callbackId);
                    return current;
                }
                throw conflict("Payment cannot accept this result in its current state");
            }
            String next = result.status().name();
            String recovery = switch (result.status()) {
                case SUCCEEDED -> "NONE";
                case FAILED -> "MANUAL_REQUIRED";
                case PROCESSING -> "AUTO";
            };
            if (result.status() == GatewayResultStatus.SUCCEEDED) {
                if ("PENDING_PAYMENT".equals(order.status())) {
                    allocate(order);
                } else if ("CLOSED".equals(order.status())) {
                    // The durable intent is part of the same local commit as late success. The
                    // provider refund is deliberately dispatched later, outside this transaction.
                    recovery = "NONE";
                } else {
                    throw conflict("Order cannot accept payment success");
                }
            }
            changed(db.update("""
                    UPDATE et_payment SET status=?,provider_transaction_id=?,recovery_status=?,last_error=?,
                        succeeded_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,version=version+1
                    WHERE id=? AND version=? AND status=?
                    """, next, result.providerTransactionId(), recovery, null,
                    result.status() == GatewayResultStatus.SUCCEEDED ? Timestamp.from(now()) : null,
                    "AUTO".equals(recovery) ? Timestamp.from(now().plusSeconds(5)) : null,
                    paymentId, current.version(), current.status()));
            if (result.status() == GatewayResultStatus.SUCCEEDED && "CLOSED".equals(order.status())) {
                ensureCompensationIntent(payment(paymentId, current.userId()));
            }
            history(paymentId, current.status(), next,
                    callbackId == null ? "GATEWAY_RESULT" : "CALLBACK_RESULT", null, callbackId);
            if (callbackId != null) markCallbackApplied(callbackId);
            return payment(paymentId, current.userId());
        }));
    }

    record RecoveryClaim(long paymentId, String leaseToken) {}

    java.util.List<RecoveryClaim> claimDueRecoveries(int requestedBatchSize) {
        requireNoTransaction();
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 100));
        return required(transactions.execute(tx -> {
            Instant clock = now();
            var ids = db.queryForList("""
                    SELECT id FROM et_payment
                    WHERE recovery_status='AUTO' AND status IN ('PROCESSING','UNKNOWN')
                      AND ((next_attempt_at IS NOT NULL AND next_attempt_at<=?)
                           OR (lease_until IS NOT NULL AND lease_until<=?))
                    ORDER BY id LIMIT %d
                    """.formatted(batchSize), Long.class, Timestamp.from(clock), Timestamp.from(clock));
            var claims = new java.util.ArrayList<RecoveryClaim>();
            for (Long id : ids) {
                var row = db.queryForMap("""
                        SELECT attempts,version,status,recovery_status,next_attempt_at,lease_until
                        FROM et_payment WHERE id=? FOR UPDATE
                        """, id);
                if (!eligibleForRecovery(row, clock)) continue;
                int attempts = ((Number) row.get("attempts")).intValue();
                int version = ((Number) row.get("version")).intValue();
                if (attempts >= 5) {
                    db.update("UPDATE et_payment SET recovery_status='MANUAL_REQUIRED',next_attempt_at=NULL,"
                            + "lease_token=NULL,lease_until=NULL,last_error='RECOVERY_ATTEMPTS_EXHAUSTED',version=version+1"
                            + " WHERE id=? AND version=?", id, version);
                    history(id, null, null, "RECOVERY_EXHAUSTED", "RECOVERY_ATTEMPTS_EXHAUSTED", null);
                    continue;
                }
                String token = java.util.UUID.randomUUID().toString();
                int changed = db.update("""
                        UPDATE et_payment SET attempts=attempts+1,lease_token=?,lease_until=?,next_attempt_at=?,version=version+1
                        WHERE id=? AND version=? AND recovery_status='AUTO' AND status IN ('PROCESSING','UNKNOWN')
                        """, token, Timestamp.from(clock.plusSeconds(30)), Timestamp.from(clock.plusSeconds(30)), id, version);
                if (changed == 1) claims.add(new RecoveryClaim(id, token));
            }
            return claims;
        }));
    }

    private static boolean eligibleForRecovery(java.util.Map<String, Object> row, Instant clock) {
        if (!"AUTO".equals(row.get("recovery_status"))
                || !("PROCESSING".equals(row.get("status")) || "UNKNOWN".equals(row.get("status")))) return false;
        Timestamp next = (Timestamp) row.get("next_attempt_at");
        Timestamp lease = (Timestamp) row.get("lease_until");
        return (next != null && !next.toInstant().isAfter(clock)) || (lease != null && !lease.toInstant().isAfter(clock));
    }

    PaymentView recoverClaim(RecoveryClaim claim) {
        requireNoTransaction();
        PaymentView payment = paymentById(claim.paymentId());
        java.util.Optional<SimulatedPaymentGateway.GatewayPayment> result;
        try {
            result = gateway.queryPayment(payment.paymentNumber());
        } catch (RuntimeException failure) {
            return recordRecoveryMiss(payment.id(), claim.leaseToken(), failure.getClass().getSimpleName());
        }
        if (result.isPresent()) return applyResult(payment.id(), result.get());
        return recordRecoveryMiss(payment.id(), claim.leaseToken(), "GATEWAY_PAYMENT_NOT_FOUND");
    }

    public PaymentView refresh(long paymentId, long userId) {
        requireNoTransaction();
        PaymentView payment = payment(paymentId, userId);
        var result = gateway.queryPayment(payment.paymentNumber());
        return result.isPresent() ? applyResult(payment.id(), result.get()) : payment(paymentId, userId);
    }

    public PaymentView manualRetry(long paymentId, long actorId, String operationKey, String reason) {
        requireNoTransaction();
        validateOperation(operationKey, reason);
        PaymentView reference = paymentById(paymentId);
        String requestHash = hashOperation(paymentId, reason);
        ManualDecision decision = required(transactions.execute(tx -> {
            LockedOrder order = lockOrder(reference.orderId());
            PaymentView current = lockPayment(paymentId);
            var prior = db.query("SELECT payment_id,request_hash FROM et_payment_history WHERE actor_id=? AND operation_key=?",
                    (rs, row) -> new Object[]{rs.getLong(1), rs.getString(2)}, actorId, operationKey);
            if (!prior.isEmpty()) {
                Object[] value = prior.getFirst();
                if (((Number) value[0]).longValue() != paymentId || !requestHash.equals(value[1])) {
                    throw conflict("Operator idempotency key was reused with a different request");
                }
                return new ManualDecision(true, eligibleForNewCharge(order));
            }
            if ("SUCCEEDED".equals(current.status())) {
                if ("CLOSED".equals(order.status())) ensureCompensationIntent(current);
                history(paymentId, current.status(), current.status(), "PAYMENT_MANUAL_RETRY", reason, null,
                        actorId, operationKey, requestHash);
                return new ManualDecision(false, false);
            } else {
                if (!"FAILED".equals(current.status()) && !"MANUAL_REQUIRED".equals(current.recoveryStatus())) {
                    throw conflict("Payment is not awaiting manual recovery");
                }
                boolean maySubmit = eligibleForNewCharge(order);
                if (maySubmit) {
                    changed(db.update("UPDATE et_payment SET status='PROCESSING',recovery_status='AUTO',next_attempt_at=?,last_error=NULL,"
                                    + "lease_token=NULL,lease_until=NULL,version=version+1 WHERE id=? AND version=?",
                            Timestamp.from(now()), paymentId, current.version()));
                }
                history(paymentId, current.status(), maySubmit ? "PROCESSING" : current.status(),
                        "PAYMENT_MANUAL_RETRY", reason, null,
                        actorId, operationKey, requestHash);
                return new ManualDecision(false, maySubmit);
            }
        }));
        PaymentView afterAudit = paymentById(paymentId);
        if ("SUCCEEDED".equals(afterAudit.status()) || decision.replay() && "FAILED".equals(afterAudit.status())) {
            return afterAudit;
        }
        var found = gateway.queryPayment(reference.paymentNumber());
        if (found.isPresent() && found.get().status() != GatewayResultStatus.FAILED) {
            return applyResult(paymentId, found.get());
        }
        if (!decision.maySubmit()) {
            return found.isPresent() ? applyResult(paymentId, found.get()) : afterAudit;
        }
        SimulatedPaymentGateway.GatewayPayment submitted;
        try {
            String orderNumber = required(db.queryForObject("SELECT order_number FROM et_order WHERE id=?",
                    String.class, reference.orderId()));
            submitted = gateway.createPayment(new SimulatedPaymentGateway.PaymentRequest(reference.paymentNumber(),
                    orderNumber, reference.amount(), reference.currency()));
        } catch (RuntimeException uncertain) {
            markRecoveryUnknown(paymentId, uncertain.getClass().getSimpleName());
            return paymentById(paymentId);
        }
        return applyResult(paymentId, submitted);
    }

    private boolean eligibleForNewCharge(LockedOrder order) {
        return "PENDING_PAYMENT".equals(order.status()) && now().isBefore(order.deadline());
    }

    private PaymentView recordRecoveryMiss(long paymentId, String token, String detail) {
        PaymentView reference = paymentById(paymentId);
        return required(transactions.execute(tx -> {
            lockOrder(reference.orderId());
            PaymentView current = lockPayment(paymentId);
            if (!"PROCESSING".equals(current.status()) && !"UNKNOWN".equals(current.status())) return current;
            String leaseCondition = token == null ? "" : " AND lease_token=?";
            Object[] args = token == null
                    ? new Object[]{detail, Timestamp.from(now().plusSeconds(5)), paymentId, current.version()}
                    : new Object[]{detail, Timestamp.from(now().plusSeconds(5)), paymentId, current.version(), token};
            int changed = db.update("UPDATE et_payment SET status='UNKNOWN',last_error=?,next_attempt_at=?,"
                    + "lease_token=NULL,lease_until=NULL,version=version+1 WHERE id=? AND version=?" + leaseCondition, args);
            if (changed == 0) return paymentById(paymentId);
            history(paymentId, current.status(), "UNKNOWN", "RECOVERY_QUERY_MISS", detail, null);
            return paymentById(paymentId);
        }));
    }

    void markUnknown(long paymentId, long userId, String detail) {
        requireNoTransaction();
        PaymentView reference = payment(paymentId, userId);
        transactions.executeWithoutResult(tx -> {
            lockOrder(reference.orderId());
            PaymentView current = lockPayment(paymentId);
            // A delayed transport error must not downgrade committed success/failure.
            if (!"PROCESSING".equals(current.status())) return;
            changed(db.update("""
                    UPDATE et_payment SET status='UNKNOWN',last_error=?,next_attempt_at=?,version=version+1
                    WHERE id=? AND status='PROCESSING' AND version=?
                    """, detail, Timestamp.from(now().plusSeconds(5)), paymentId, current.version()));
            history(paymentId, current.status(), "UNKNOWN", "GATEWAY_UNCERTAIN", detail, null);
        });
    }

    private void allocate(LockedOrder order) {
        var reservation = db.queryForMap("""
                SELECT ticket_tier_id,quantity,status FROM et_inventory_reservation WHERE order_id=? FOR UPDATE
                """, order.id());
        if (!"RESERVED".equals(reservation.get("status"))
                || ((Number) reservation.get("ticket_tier_id")).longValue() != order.tierId()
                || ((Number) reservation.get("quantity")).intValue() != order.quantity()) {
            throw new IllegalStateException("Pending order does not own the expected reserved inventory");
        }
        db.queryForObject("SELECT id FROM et_ticket_tier WHERE id=? FOR UPDATE", Long.class, order.tierId());
        changed(db.update("UPDATE et_order SET status='PAID' WHERE id=? AND status='PENDING_PAYMENT'", order.id()));
        notificationOutbox.orderPaid(order.id(), order.userId());
        changed(db.update("UPDATE et_inventory_reservation SET status='CONFIRMED' WHERE order_id=? AND status='RESERVED'",
                order.id()));
        changed(db.update("""
                UPDATE et_ticket_tier SET reserved=reserved-?,allocated=allocated+? WHERE id=? AND reserved>=?
                """, order.quantity(), order.quantity(), order.tierId(), order.quantity()));
        Integer violations = db.queryForObject("""
                SELECT COUNT(*) FROM et_ticket_tier WHERE id=? AND
                    (available<0 OR reserved<0 OR allocated<0 OR available+reserved+allocated<>capacity)
                """, Integer.class, order.tierId());
        if (violations == null || violations != 0) throw new IllegalStateException("Inventory conservation violated");
    }

    private void ensureCompensationIntent(PaymentView payment) {
        var existing = db.queryForList("SELECT id FROM et_refund WHERE payment_id=?", Long.class, payment.id());
        if (existing.isEmpty()) {
            long refundId = IdWorker.getId();
            db.update("""
                    INSERT INTO et_refund(id,refund_number,payment_id,reason,amount,currency,status,
                        recovery_status,attempts,next_attempt_at)
                    VALUES (?, ?,?,'LATE_PAYMENT',?,?,'REQUESTED','AUTO',0,?)
                    """, refundId, "ER" + refundId, payment.id(), payment.amount(), payment.currency(), Timestamp.from(now()));
            db.update("""
                    INSERT INTO et_payment_history(id,payment_id,refund_id,action,from_status,to_status,source,detail)
                    VALUES (?,?,?,'LATE_REFUND_REQUESTED',NULL,'REQUESTED','PAYMENT_SERVICE','ORDER_ALREADY_CLOSED')
                    """, IdWorker.getId(), payment.id(), refundId);
        }
        if (!"NONE".equals(payment.recoveryStatus()) || payment.lastError() != null) {
            db.update("UPDATE et_payment SET recovery_status='NONE',last_error=NULL,next_attempt_at=NULL,"
                    + "lease_token=NULL,lease_until=NULL,version=version+1 WHERE id=?", payment.id());
        }
    }

    private void markRecoveryUnknown(long paymentId, String detail) {
        PaymentView reference = paymentById(paymentId);
        transactions.executeWithoutResult(tx -> {
            lockOrder(reference.orderId());
            PaymentView current = lockPayment(paymentId);
            if ("SUCCEEDED".equals(current.status()) || "FAILED".equals(current.status())) return;
            db.update("UPDATE et_payment SET status='UNKNOWN',recovery_status='AUTO',last_error=?,next_attempt_at=?,"
                    + "lease_token=NULL,lease_until=NULL,version=version+1 WHERE id=?", bounded(detail),
                    Timestamp.from(now().plusSeconds(5)), paymentId);
        });
    }

    private void validateResult(LockedOrder order, PaymentView payment, SimulatedPaymentGateway.GatewayPayment result) {
        if (result == null || result.status() == null || !"simulated".equals(payment.provider())
                || order.id() != payment.orderId() || order.userId() != payment.userId()
                || order.amount() != payment.amount() || !order.currency().equals(payment.currency())
                || !payment.paymentNumber().equals(result.paymentNumber())
                || !order.number().equals(result.orderReference()) || payment.amount() != result.amount()
                || !payment.currency().equals(result.currency()) || result.providerTransactionId() == null
                || result.providerTransactionId().isBlank() || result.providerTransactionId().length() > 64
                || result.providerTransactionId().chars().anyMatch(c -> c < 0x21 || c > 0x7e)
                || (payment.providerTransactionId() != null
                    && !payment.providerTransactionId().equals(result.providerTransactionId()))) {
            throw conflict("Gateway result does not match the immutable payment binding");
        }
    }

    private LockedOrder lockOrder(long orderId) {
        try {
            return db.queryForObject("""
                    SELECT id,order_number,user_id,ticket_tier_id,quantity,total_amount,currency,status,payment_deadline
                    FROM et_order WHERE id=? FOR UPDATE
                    """, (rs, row) -> new LockedOrder(rs.getLong("id"), rs.getString("order_number"),
                    rs.getLong("user_id"), rs.getLong("ticket_tier_id"), rs.getInt("quantity"),
                    rs.getLong("total_amount"), rs.getString("currency"), rs.getString("status"),
                    rs.getTimestamp("payment_deadline").toInstant()), orderId);
        } catch (EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }

    private PaymentView lockPayment(long id) {
        return required(db.queryForObject("SELECT * FROM et_payment WHERE id=? FOR UPDATE", (rs, row) -> map(rs), id));
    }

    private PaymentView paymentById(long paymentId) {
        try {
            return db.queryForObject("SELECT * FROM et_payment WHERE id=?", (rs, row) -> map(rs), paymentId);
        } catch (EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
    }

    private boolean lockCallbackForApply(long callbackId) {
        String status = required(db.queryForObject("SELECT status FROM et_payment_callback WHERE id=? FOR UPDATE",
                String.class, callbackId));
        if ("APPLIED".equals(status)) return false;
        if (!"RECEIVED".equals(status)) throw conflict("Callback receipt is no longer applicable");
        return true;
    }

    private void markCallbackApplied(long callbackId) {
        changed(db.update("UPDATE et_payment_callback SET status='APPLIED',recovery_status='NONE',"
                + "next_attempt_at=NULL,applied_at=?,last_error=NULL WHERE id=? AND status='RECEIVED'",
                Timestamp.from(now()), callbackId));
    }

    private void acknowledgeTerminalCallback(long paymentId, String status, long callbackId) {
        history(paymentId, status, status, "CALLBACK_CONFIRMED_TERMINAL", null, callbackId);
        markCallbackApplied(callbackId);
    }

    private PaymentView replay(PaymentView existing, long orderId, String key) {
        if (existing.orderId() != orderId || !existing.idempotencyKey().equals(key)
                || !existing.requestHash().equals(hash(orderId))) throw conflict("Payment idempotency conflict");
        return existing;
    }

    private void history(long id, String before, String after, String action, String detail, Long callbackId) {
        history(id, before, after, action, detail, callbackId, null, null, null);
    }

    private void history(long id, String before, String after, String action, String detail, Long callbackId,
            Long actorId, String operationKey, String requestHash) {
        String source = callbackId == null ? "PAYMENT_SERVICE" : "PAYMENT_CALLBACK";
        db.update("""
                INSERT INTO et_payment_history(id,payment_id,callback_id,action,from_status,to_status,source,
                    actor_id,operation_key,request_hash,detail)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, IdWorker.getId(), id, callbackId, action, before, after, source, actorId, operationKey, requestHash, detail);
    }

    private PaymentView map(ResultSet rs) throws SQLException {
        return new PaymentView(rs.getLong("id"), rs.getString("payment_number"), rs.getLong("order_id"),
                rs.getLong("user_id"), rs.getString("provider"), rs.getLong("amount"), rs.getString("currency"),
                rs.getString("status"), rs.getString("provider_transaction_id"), rs.getString("recovery_status"),
                rs.getString("last_error"), rs.getInt("version"), rs.getString("idempotency_key"), rs.getString("request_hash"));
    }

    private Instant now() {
        return required(db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class)).toInstant();
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment orchestration must be invoked outside a transaction");
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128 || key.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 16 to 128 visible ASCII characters");
        }
    }

    private static String hash(long orderId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("simulated:" + orderId).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hashOperation(long id, String reason) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + reason).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validateOperation(String key, String reason) {
        validateKey(key);
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason of 1 to 255 characters is required");
        }
    }

    private static String bounded(String detail) {
        return detail == null ? null : detail.substring(0, Math.min(255, detail.length()));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("Payment transition did not update exactly one row");
    }

    private static <T> T required(T value) { return Objects.requireNonNull(value, "Missing transaction result"); }

    private record Prepared(PaymentView payment, String orderNumber, boolean created) {}
    private record ManualDecision(boolean replay, boolean maySubmit) {}
    private record LockedOrder(long id, String number, long userId, long tierId, int quantity,
            long amount, String currency, String status, Instant deadline) {}
    public record PaymentView(long id, String paymentNumber, long orderId, long userId, String provider,
            long amount, String currency, String status, String providerTransactionId, String recoveryStatus,
            String lastError, @JsonIgnore int version, @JsonIgnore String idempotencyKey, @JsonIgnore String requestHash) {}
}
