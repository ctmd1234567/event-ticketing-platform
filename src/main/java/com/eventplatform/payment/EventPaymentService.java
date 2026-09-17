package com.eventplatform.payment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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

/** Initial payment orchestration only; recovery workers, callbacks and refunds are later slices. */
@Service
public class EventPaymentService {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final SimulatedPaymentGateway gateway;

    public EventPaymentService(JdbcTemplate db, PlatformTransactionManager manager,
            SimulatedPaymentGateway gateway) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
        this.gateway = gateway;
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
        history(id, null, "PROCESSING", "PAYMENT_STARTED", null);
        return new Prepared(payment(id, userId), order.number(), true);
    }

    // Package-private: only trusted gateway results may reach this seam. It is not a callback API.
    PaymentView applyResult(long paymentId, SimulatedPaymentGateway.GatewayPayment result) {
        requireNoTransaction();
        long orderId = required(db.queryForObject("SELECT order_id FROM et_payment WHERE id=?", Long.class, paymentId));
        return required(transactions.execute(tx -> {
            LockedOrder order = lockOrder(orderId);
            PaymentView current = lockPayment(paymentId);
            validateResult(order, current, result);
            if ("SUCCEEDED".equals(current.status())) {
                if (result.status() != GatewayResultStatus.SUCCEEDED) {
                    throw conflict("Contradictory result for a successful payment");
                }
                return current;
            }
            if (!"PROCESSING".equals(current.status()) && !"UNKNOWN".equals(current.status())) {
                if (current.status().equals(result.status().name())) return current;
                throw conflict("Payment cannot accept this result in its current state");
            }
            String next = result.status().name();
            String recovery = result.status() == GatewayResultStatus.PROCESSING ? "AUTO" : "NONE";
            String detail = null;
            if (result.status() == GatewayResultStatus.SUCCEEDED) {
                if ("PENDING_PAYMENT".equals(order.status())) {
                    allocate(order);
                } else if ("CLOSED".equals(order.status())) {
                    // Visible, safe intermediate boundary until the authorized 6.6 increment.
                    recovery = "MANUAL_REQUIRED";
                    detail = "LATE_PAYMENT_COMPENSATION_NOT_IMPLEMENTED";
                } else {
                    throw conflict("Order cannot accept payment success");
                }
            }
            changed(db.update("""
                    UPDATE et_payment SET status=?,provider_transaction_id=?,recovery_status=?,last_error=?,
                        succeeded_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL,version=version+1
                    WHERE id=? AND version=? AND status=?
                    """, next, result.providerTransactionId(), recovery, detail,
                    result.status() == GatewayResultStatus.SUCCEEDED ? Timestamp.from(now()) : null,
                    "AUTO".equals(recovery) ? Timestamp.from(now().plusSeconds(5)) : null,
                    paymentId, current.version(), current.status()));
            history(paymentId, current.status(), next, "GATEWAY_RESULT", detail);
            return payment(paymentId, current.userId());
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
            history(paymentId, current.status(), "UNKNOWN", "GATEWAY_UNCERTAIN", detail);
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

    private PaymentView replay(PaymentView existing, long orderId, String key) {
        if (existing.orderId() != orderId || !existing.idempotencyKey().equals(key)
                || !existing.requestHash().equals(hash(orderId))) throw conflict("Payment idempotency conflict");
        return existing;
    }

    private void history(long id, String before, String after, String action, String detail) {
        db.update("""
                INSERT INTO et_payment_history(id,payment_id,action,from_status,to_status,source,detail)
                VALUES (?,?,?,?,?,'PAYMENT_SERVICE',?)
                """, IdWorker.getId(), id, action, before, after, detail);
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

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("Payment transition did not update exactly one row");
    }

    private static <T> T required(T value) { return Objects.requireNonNull(value, "Missing transaction result"); }

    private record Prepared(PaymentView payment, String orderNumber, boolean created) {}
    private record LockedOrder(long id, String number, long userId, long tierId, int quantity,
            long amount, String currency, String status, Instant deadline) {}
    public record PaymentView(long id, String paymentNumber, long orderId, long userId, String provider,
            long amount, String currency, String status, String providerTransactionId, String recoveryStatus,
            String lastError, @JsonIgnore int version, @JsonIgnore String idempotencyKey, @JsonIgnore String requestHash) {}
}
