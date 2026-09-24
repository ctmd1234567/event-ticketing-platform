package com.eventplatform.payment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable late-payment compensation. No provider call runs inside a local transaction. */
@Service
public class EventRefundService {
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final SimulatedPaymentGateway gateway;

    public EventRefundService(JdbcTemplate db, PlatformTransactionManager manager, SimulatedPaymentGateway gateway) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
        this.gateway = gateway;
    }

    public RefundView refund(long refundId, long userId) {
        try {
            return db.queryForObject(selectRefund() + " WHERE r.id=? AND p.user_id=?", this::map, refundId, userId);
        } catch (EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found");
        }
    }

    /** One full refund per successful payment; the provider call uses the existing recovery path. */
    public RefundView requestFullRefund(long paymentId, long userId) {
        requireNoTransaction();
        var ownership = db.queryForList("SELECT order_id FROM et_payment WHERE id=? AND user_id=?",
                Long.class, paymentId, userId);
        if (ownership.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        long orderId = ownership.getFirst();
        RefundIntent intent = required(transactions.execute(tx -> {
            String orderStatus = lockOrder(orderId);
            PaymentBinding payment = lockPayment(paymentId);
            var existing = db.queryForList("SELECT id FROM et_refund WHERE payment_id=?", Long.class, paymentId);
            if (!existing.isEmpty()) {
                RefundView prior = lockRefund(existing.getFirst());
                if (!"USER_REQUEST".equals(refundReason(prior.id()))) {
                    throw conflict("Payment already has a compensation refund");
                }
                return new RefundIntent(prior.id(), false);
            }
            if (!("PAID".equals(orderStatus) || "FULFILLED".equals(orderStatus))
                    || !"SUCCEEDED".equals(payment.status())) {
                throw conflict("Only a paid order can request a full refund");
            }
            String reservation = db.queryForObject(
                    "SELECT status FROM et_inventory_reservation WHERE order_id=? FOR UPDATE",
                    String.class, orderId);
            if (!"CONFIRMED".equals(reservation)) throw conflict("Paid inventory is not confirmed");
            long id = IdWorker.getId();
            changed(db.update("UPDATE et_order SET status='REFUNDING' WHERE id=? AND status=?",
                    orderId, orderStatus));
            changed(db.update("""
                    INSERT INTO et_refund(id,refund_number,payment_id,reason,amount,currency,status,
                        recovery_status,attempts,next_attempt_at)
                    VALUES (?, ?,?,'USER_REQUEST',?,?,'REQUESTED','AUTO',0,?)
                    """, id, "ER" + id, paymentId, payment.amount(), payment.currency(), Timestamp.from(now())));
            history(id, null, "REQUESTED", "USER_REFUND_REQUESTED", null, null,
                    userId, null, null);
            return new RefundIntent(id, true);
        }));
        RefundView current = refundById(intent.id());
        return intent.created() ? recover(current, null) : current;
    }

    private String refundReason(long refundId) {
        return db.queryForObject("SELECT reason FROM et_refund WHERE id=?", String.class, refundId);
    }

    RefundView refundByNumber(String refundNumber) {
        try {
            return db.queryForObject(selectRefund() + " WHERE r.refund_number=?", this::map, refundNumber);
        } catch (EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found");
        }
    }

    public RefundView refresh(long refundId, long userId) {
        requireNoTransaction();
        RefundView current = refund(refundId, userId);
        var result = gateway.queryRefund(current.refundNumber());
        return result.isPresent() ? applyResult(current.id(), result.get(), null) : refund(refundId, userId);
    }

    record RecoveryClaim(long refundId, String leaseToken) {}

    List<RecoveryClaim> claimDueRecoveries(int requestedBatchSize) {
        requireNoTransaction();
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 100));
        return required(transactions.execute(tx -> {
            Instant clock = now();
            var ids = db.queryForList("""
                    SELECT id FROM et_refund
                    WHERE recovery_status='AUTO' AND status IN ('REQUESTED','PROCESSING','UNKNOWN')
                      AND ((next_attempt_at IS NOT NULL AND next_attempt_at<=?)
                           OR (lease_until IS NOT NULL AND lease_until<=?))
                    ORDER BY id LIMIT %d
                    """.formatted(batchSize), Long.class, Timestamp.from(clock), Timestamp.from(clock));
            var claims = new ArrayList<RecoveryClaim>();
            for (Long id : ids) {
                Map<String, Object> row = db.queryForMap("""
                        SELECT attempts,version,status,recovery_status,next_attempt_at,lease_until
                        FROM et_refund WHERE id=? FOR UPDATE
                        """, id);
                if (!eligible(row, clock)) continue;
                int attempts = ((Number) row.get("attempts")).intValue();
                int version = ((Number) row.get("version")).intValue();
                if (attempts >= MAX_ATTEMPTS) {
                    db.update("UPDATE et_refund SET recovery_status='MANUAL_REQUIRED',next_attempt_at=NULL,"
                            + "lease_token=NULL,lease_until=NULL,last_error='REFUND_ATTEMPTS_EXHAUSTED',version=version+1"
                            + " WHERE id=? AND version=?", id, version);
                    history(id, null, null, "REFUND_RECOVERY_EXHAUSTED", "REFUND_ATTEMPTS_EXHAUSTED", null,
                            null, null, null);
                    continue;
                }
                String token = UUID.randomUUID().toString();
                int changed = db.update("""
                        UPDATE et_refund SET attempts=attempts+1,lease_token=?,lease_until=?,next_attempt_at=?,version=version+1
                        WHERE id=? AND version=? AND recovery_status='AUTO'
                          AND status IN ('REQUESTED','PROCESSING','UNKNOWN')
                        """, token, Timestamp.from(clock.plusSeconds(30)), Timestamp.from(clock.plusSeconds(30)), id, version);
                if (changed == 1) claims.add(new RecoveryClaim(id, token));
            }
            return claims;
        }));
    }

    RefundView recoverClaim(RecoveryClaim claim) {
        requireNoTransaction();
        return recover(refundById(claim.refundId()), claim.leaseToken());
    }

    public RefundView manualRetry(long refundId, long actorId, String operationKey, String reason) {
        requireNoTransaction();
        validateOperation(operationKey, reason);
        RefundView reference = refundById(refundId);
        String requestHash = operationHash(refundId, reason);
        boolean replay = required(transactions.execute(tx -> {
            lockOrder(reference.orderId());
            lockPayment(reference.paymentId());
            RefundView current = lockRefund(refundId);
            var prior = db.query("SELECT refund_id,request_hash FROM et_payment_history WHERE actor_id=? AND operation_key=?",
                    (rs, row) -> new Object[]{rs.getLong(1), rs.getString(2)}, actorId, operationKey);
            if (!prior.isEmpty()) {
                Object[] value = prior.getFirst();
                if (((Number) value[0]).longValue() != refundId || !requestHash.equals(value[1])) {
                    throw conflict("Operator idempotency key was reused with a different request");
                }
                return true;
            }
            if ("SUCCEEDED".equals(current.status())) {
                history(refundId, current.status(), current.status(), "REFUND_MANUAL_RETRY", reason, null,
                        actorId, operationKey, requestHash);
                return false;
            }
            if (!"FAILED".equals(current.status()) && !"MANUAL_REQUIRED".equals(current.recoveryStatus())) {
                throw conflict("Refund is not awaiting manual recovery");
            }
            changed(db.update("UPDATE et_refund SET status='PROCESSING',recovery_status='AUTO',next_attempt_at=?,"
                            + "last_error=NULL,lease_token=NULL,lease_until=NULL,version=version+1 WHERE id=? AND version=?",
                    Timestamp.from(now()), refundId, current.version()));
            history(refundId, current.status(), "PROCESSING", "REFUND_MANUAL_RETRY", reason, null,
                    actorId, operationKey, requestHash);
            return false;
        }));
        RefundView afterAudit = refundById(refundId);
        if ("SUCCEEDED".equals(afterAudit.status()) || replay && "FAILED".equals(afterAudit.status())) return afterAudit;
        return recover(afterAudit, null);
    }

    RefundView applyCallbackResult(long refundId, SimulatedPaymentGateway.GatewayRefund result, long callbackId) {
        return applyResult(refundId, result, callbackId);
    }

    private RefundView recover(RefundView refund, String leaseToken) {
        java.util.Optional<SimulatedPaymentGateway.GatewayRefund> found;
        try {
            found = gateway.queryRefund(refund.refundNumber());
        } catch (RuntimeException uncertain) {
            return recordUnknown(refund.id(), leaseToken, uncertain.getClass().getSimpleName());
        }
        if (found.isPresent() && !(found.get().status() == GatewayResultStatus.FAILED && leaseToken == null)) {
            return applyResult(refund.id(), found.get(), null);
        }
        SimulatedPaymentGateway.GatewayRefund submitted;
        try {
            submitted = gateway.createRefund(request(refund));
        } catch (RuntimeException uncertain) {
            return recordUnknown(refund.id(), leaseToken, uncertain.getClass().getSimpleName());
        }
        return applyResult(refund.id(), submitted, null);
    }

    private SimulatedPaymentGateway.RefundRequest request(RefundView refund) {
        return new SimulatedPaymentGateway.RefundRequest(refund.refundNumber(), refund.paymentNumber(),
                refund.amount(), refund.currency());
    }

    private RefundView applyResult(long refundId, SimulatedPaymentGateway.GatewayRefund result, Long callbackId) {
        requireNoTransaction();
        RefundView reference = refundById(refundId);
        return required(transactions.execute(tx -> {
            String orderStatus = lockOrder(reference.orderId());
            PaymentBinding payment = lockPayment(reference.paymentId());
            RefundView current = lockRefund(refundId);
            boolean applyCallback = callbackId == null || lockCallbackForApply(callbackId);
            validateBinding(orderStatus, payment, current, result);
            if (!applyCallback) return current;
            String next = result.status().name();
            if ("SUCCEEDED".equals(current.status())) {
                if (result.status() != GatewayResultStatus.SUCCEEDED) throw conflict("Contradictory result for a successful refund");
                if (callbackId != null) acknowledgeTerminalCallback(current, callbackId);
                return current;
            }
            if ("FAILED".equals(current.status()) && result.status() == GatewayResultStatus.FAILED) {
                if (callbackId != null) acknowledgeTerminalCallback(current, callbackId);
                return current;
            }
            if (!List.of("REQUESTED", "PROCESSING", "UNKNOWN", "FAILED").contains(current.status())) {
                throw conflict("Refund cannot accept this result in its current state");
            }
            if ("FAILED".equals(current.status()) && result.status() != GatewayResultStatus.SUCCEEDED) {
                throw conflict("A failed refund requires an audited retry before another nonterminal result");
            }
            String recovery = switch (result.status()) {
                case SUCCEEDED -> "NONE";
                case FAILED -> "MANUAL_REQUIRED";
                case PROCESSING -> "AUTO";
            };
            changed(db.update("""
                    UPDATE et_refund SET status=?,provider_refund_id=?,recovery_status=?,last_error=NULL,
                        succeeded_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,version=version+1
                    WHERE id=? AND version=? AND status=?
                    """, next, result.providerRefundId(), recovery,
                    result.status() == GatewayResultStatus.SUCCEEDED ? Timestamp.from(now()) : null,
                    "AUTO".equals(recovery) ? Timestamp.from(now().plusSeconds(5)) : null,
                    refundId, current.version(), current.status()));
            history(refundId, current.status(), next,
                    callbackId == null ? "REFUND_GATEWAY_RESULT" : "REFUND_CALLBACK_RESULT", null, callbackId,
                    null, null, null);
            if (result.status() == GatewayResultStatus.SUCCEEDED
                    && "USER_REQUEST".equals(refundReason(refundId))) {
                changed(db.update("UPDATE et_order SET status='REFUNDED' WHERE id=? AND status='REFUNDING'",
                        current.orderId()));
            }
            if (callbackId != null) markCallbackApplied(callbackId);
            return refundById(refundId);
        }));
    }

    private RefundView recordUnknown(long refundId, String leaseToken, String detail) {
        RefundView reference = refundById(refundId);
        return required(transactions.execute(tx -> {
            lockOrder(reference.orderId());
            lockPayment(reference.paymentId());
            RefundView current = lockRefund(refundId);
            if ("SUCCEEDED".equals(current.status()) || "FAILED".equals(current.status())) return current;
            String leaseClause = leaseToken == null ? "" : " AND lease_token=?";
            Object[] args = leaseToken == null
                    ? new Object[]{bounded(detail), Timestamp.from(now().plusSeconds(5)), refundId, current.version()}
                    : new Object[]{bounded(detail), Timestamp.from(now().plusSeconds(5)), refundId, current.version(), leaseToken};
            int count = db.update("UPDATE et_refund SET status='UNKNOWN',recovery_status='AUTO',last_error=?,next_attempt_at=?,"
                    + "lease_token=NULL,lease_until=NULL,version=version+1 WHERE id=? AND version=?" + leaseClause, args);
            if (count == 1) history(refundId, current.status(), "UNKNOWN", "REFUND_GATEWAY_UNCERTAIN", detail, null,
                    null, null, null);
            return refundById(refundId);
        }));
    }

    private static boolean eligible(Map<String, Object> row, Instant clock) {
        if (!"AUTO".equals(row.get("recovery_status"))
                || !List.of("REQUESTED", "PROCESSING", "UNKNOWN").contains(String.valueOf(row.get("status")))) return false;
        Timestamp next = (Timestamp) row.get("next_attempt_at");
        Timestamp lease = (Timestamp) row.get("lease_until");
        return next != null && !next.toInstant().isAfter(clock) || lease != null && !lease.toInstant().isAfter(clock);
    }

    private void validateBinding(String orderStatus, PaymentBinding payment, RefundView refund,
            SimulatedPaymentGateway.GatewayRefund result) {
        String reason = refundReason(refund.id());
        boolean validOrder = "LATE_PAYMENT".equals(reason) && "CLOSED".equals(orderStatus)
                || "USER_REQUEST".equals(reason)
                && ("REFUNDING".equals(orderStatus) || "REFUNDED".equals(orderStatus));
        if (!validOrder || !"SUCCEEDED".equals(payment.status())
                || result == null || result.status() == null
                || payment.id() != refund.paymentId() || payment.amount() != refund.amount()
                || !payment.currency().equals(refund.currency())
                || !refund.refundNumber().equals(result.refundNumber())
                || !refund.paymentNumber().equals(result.paymentNumber())
                || refund.amount() != result.amount() || !refund.currency().equals(result.currency())
                || result.providerRefundId() == null || result.providerRefundId().isBlank()
                || result.providerRefundId().length() > 64
                || result.providerRefundId().chars().anyMatch(c -> c < 0x21 || c > 0x7e)
                || refund.providerRefundId() != null && !refund.providerRefundId().equals(result.providerRefundId())) {
            throw conflict("Gateway result does not match the immutable refund binding");
        }
    }

    private String lockOrder(long orderId) {
        return required(db.queryForObject("SELECT status FROM et_order WHERE id=? FOR UPDATE", String.class, orderId));
    }

    private PaymentBinding lockPayment(long paymentId) {
        return required(db.queryForObject("SELECT id,status,amount,currency FROM et_payment WHERE id=? FOR UPDATE",
                (rs, row) -> new PaymentBinding(rs.getLong("id"), rs.getString("status"), rs.getLong("amount"),
                        rs.getString("currency")), paymentId));
    }

    private RefundView lockRefund(long refundId) {
        return required(db.queryForObject(selectRefund() + " WHERE r.id=? FOR UPDATE", this::map, refundId));
    }

    private boolean lockCallbackForApply(long callbackId) {
        String status = required(db.queryForObject("SELECT status FROM et_payment_callback WHERE id=? FOR UPDATE",
                String.class, callbackId));
        if ("APPLIED".equals(status)) return false;
        if (!"RECEIVED".equals(status)) throw conflict("Callback receipt is no longer applicable");
        return true;
    }

    private void acknowledgeTerminalCallback(RefundView refund, long callbackId) {
        history(refund.id(), refund.status(), refund.status(), "REFUND_CALLBACK_CONFIRMED_TERMINAL", null,
                callbackId, null, null, null);
        markCallbackApplied(callbackId);
    }

    private void markCallbackApplied(long callbackId) {
        changed(db.update("UPDATE et_payment_callback SET status='APPLIED',recovery_status='NONE',"
                + "next_attempt_at=NULL,applied_at=?,last_error=NULL WHERE id=? AND status='RECEIVED'",
                Timestamp.from(now()), callbackId));
    }

    private void history(long refundId, String before, String after, String action, String detail, Long callbackId,
            Long actorId, String operationKey, String requestHash) {
        db.update("""
                INSERT INTO et_payment_history(id,payment_id,refund_id,callback_id,action,from_status,to_status,source,
                    actor_id,operation_key,request_hash,detail)
                SELECT ?,r.payment_id,r.id,?,?,?,?,?,?,?,?,? FROM et_refund r WHERE r.id=?
                """, IdWorker.getId(), callbackId, action, before, after,
                callbackId == null ? "REFUND_SERVICE" : "PAYMENT_CALLBACK", actorId, operationKey, requestHash,
                bounded(detail), refundId);
    }

    private RefundView refundById(long refundId) {
        try {
            return db.queryForObject(selectRefund() + " WHERE r.id=?", this::map, refundId);
        } catch (EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found");
        }
    }

    private String selectRefund() {
        return """
                SELECT r.*,p.user_id,p.order_id,p.payment_number,o.order_number
                FROM et_refund r JOIN et_payment p ON p.id=r.payment_id JOIN et_order o ON o.id=p.order_id
                """;
    }

    private RefundView map(ResultSet rs, int row) throws SQLException {
        return new RefundView(rs.getLong("id"), rs.getString("refund_number"), rs.getLong("payment_id"),
                rs.getLong("order_id"), rs.getLong("user_id"), rs.getString("payment_number"),
                rs.getString("order_number"), rs.getLong("amount"), rs.getString("currency"), rs.getString("status"),
                rs.getString("provider_refund_id"), rs.getString("recovery_status"), rs.getString("last_error"),
                rs.getInt("version"));
    }

    private Instant now() {
        return required(db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class)).toInstant();
    }

    private static String operationHash(long refundId, String reason) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((refundId + ":" + reason).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validateOperation(String key, String reason) {
        if (key == null || key.length() < 16 || key.length() > 128
                || key.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 16 to 128 visible ASCII characters");
        }
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason of 1 to 255 characters is required");
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Refund orchestration must be invoked outside a transaction");
        }
    }

    private static String bounded(String detail) {
        return detail == null ? null : detail.substring(0, Math.min(255, detail.length()));
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("Refund transition did not update exactly one row");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static <T> T required(T value) {
        return Objects.requireNonNull(value, "Missing transaction result");
    }

    private record PaymentBinding(long id, String status, long amount, String currency) {}
    private record RefundIntent(long id, boolean created) {}

    public record RefundView(long id, String refundNumber, long paymentId, long orderId, long userId,
            String paymentNumber, String orderReference, long amount, String currency, String status,
            String providerRefundId, String recoveryStatus, String lastError, @JsonIgnore int version) {}
}
