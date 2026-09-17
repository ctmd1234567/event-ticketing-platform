package com.eventplatform.payment;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Durable payment-callback receipt boundary. HTTP routing is deliberately deferred to 6.7. */
@Service
public class PaymentCallbackService {
    private final JdbcTemplate db;
    private final TransactionTemplate receipts;
    private final EventPaymentService payments;
    private final String secret;
    private final Duration freshness;

    public PaymentCallbackService(JdbcTemplate db, PlatformTransactionManager manager, EventPaymentService payments,
            @Value("${app.simulated-gateway.callback-secret:}") String secret,
            @Value("${app.simulated-gateway.callback-freshness-seconds:300}") long freshnessSeconds) {
        this.db = db;
        this.payments = payments;
        this.secret = secret == null ? "" : secret;
        this.freshness = Duration.ofSeconds(Math.max(1, freshnessSeconds));
        this.receipts = new TransactionTemplate(manager);
        this.receipts.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CallbackView receive(CallbackCommand command) {
        requireNoTransaction();
        validateAuthentication(command);
        CallbackView receipt = persistReceipt(command);
        applyReceived(receipt.id());
        return callback(receipt.id());
    }

    /** Restart-safe receipt application after authentication and receipt commit already happened. */
    void applyReceived(long receiptId) {
        CallbackView receipt = callback(receiptId);
        if (!"RECEIVED".equals(receipt.status())) return;
        try {
            EventPaymentService.PaymentView payment = payments.paymentByNumber(receipt.businessNumber());
            validateBinding(payment, receipt);
            payments.applyCallbackResult(payment.id(), gatewayPayment(receipt), receipt.id());
        } catch (ResponseStatusException rejected) {
            if (rejected.getStatusCode() == HttpStatus.NOT_FOUND) unmatched(receipt.id(), rejected.getReason());
            else reject(receipt.id(), rejected.getReason());
        }
    }

    void recoverDueReceipts(int requestedBatchSize) {
        requireNoTransaction();
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 100));
        var ids = db.queryForList("""
                SELECT id FROM et_payment_callback WHERE status='RECEIVED' AND recovery_status='AUTO'
                  AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT %d
                """.formatted(batchSize), Long.class);
        for (Long id : ids) {
            boolean claimed = Objects.requireNonNull(receipts.execute(tx -> {
                var row = db.queryForMap("SELECT attempts,status,recovery_status,next_attempt_at"
                        + " FROM et_payment_callback WHERE id=? FOR UPDATE", id);
                if (!"RECEIVED".equals(row.get("status")) || !"AUTO".equals(row.get("recovery_status"))) return false;
                Timestamp next = (Timestamp) row.get("next_attempt_at");
                if (next == null || next.toInstant().isAfter(Instant.now())) return false;
                int attempts = ((Number) row.get("attempts")).intValue();
                if (attempts >= 5) {
                    db.update("UPDATE et_payment_callback SET recovery_status='MANUAL_REQUIRED',next_attempt_at=NULL,"
                            + "last_error='CALLBACK_ATTEMPTS_EXHAUSTED' WHERE id=?", id);
                    return false;
                }
                return db.update("UPDATE et_payment_callback SET attempts=attempts+1,next_attempt_at=? WHERE id=?"
                        + " AND status='RECEIVED' AND recovery_status='AUTO'", Timestamp.from(Instant.now().plusSeconds(5)), id) == 1;
            }));
            if (claimed) {
                try { applyReceived(id); }
                catch (RuntimeException transientFailure) { recordRetryFailure(id, transientFailure.getClass().getSimpleName()); }
            }
        }
    }

    public CallbackView callback(long id) {
        try {
            return db.queryForObject("SELECT * FROM et_payment_callback WHERE id=?", (rs, row) -> map(rs), id);
        } catch (org.springframework.dao.EmptyResultDataAccessException absent) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Callback receipt not found");
        }
    }

    private CallbackView persistReceipt(CallbackCommand command) {
        String hash = sha256(command.rawBody());
        try {
            return Objects.requireNonNull(receipts.execute(tx -> {
                String initial = "PAYMENT".equals(command.kind()) ? "RECEIVED" : "REJECTED";
                String reason = "PAYMENT".equals(command.kind()) ? null : "REFUND_CALLBACK_NOT_IMPLEMENTED";
                String recovery = "RECEIVED".equals(initial) ? "AUTO" : "NONE";
                Timestamp nextAttempt = "RECEIVED".equals(initial) ? Timestamp.from(Instant.now().plusSeconds(5)) : null;
                long id = IdWorker.getId();
                db.update("""
                        INSERT INTO et_payment_callback(id,provider,event_id,kind,business_number,payload_hash,
                          provider_result_id,order_reference,amount,currency,result_status,status,reason,attempts,
                          next_attempt_at,recovery_status)
                        VALUES (?,?,?, ?,?,?, ?,?,?,?, ?,?,?,1,?,?)
                        """, id, command.provider(), command.eventId(), command.kind(), command.businessNumber(), hash,
                        command.providerResultId(), command.orderReference(), command.amount(), command.currency(),
                        command.status().name(), initial, reason, nextAttempt, recovery);
                return callback(id);
            }));
        } catch (DuplicateKeyException duplicate) {
            CallbackView existing = db.queryForObject("SELECT * FROM et_payment_callback WHERE provider=? AND event_id=?",
                    (rs, row) -> map(rs), command.provider(), command.eventId());
            if (!existing.payloadHash().equals(hash)) throw conflict("Callback event ID was reused with a different payload");
            return existing;
        }
    }

    private void reject(long id, String reason) {
        receipts.executeWithoutResult(tx -> db.update("UPDATE et_payment_callback SET status='REJECTED',reason=?,"
                + "recovery_status='NONE',next_attempt_at=NULL,last_error=NULL WHERE id=? AND status='RECEIVED'", bounded(reason), id));
    }

    private void unmatched(long id, String reason) {
        receipts.executeWithoutResult(tx -> db.update("UPDATE et_payment_callback SET status='UNMATCHED',reason=?,"
                + "recovery_status='MANUAL_REQUIRED',next_attempt_at=NULL WHERE id=? AND status='RECEIVED'", bounded(reason), id));
    }

    private void recordRetryFailure(long id, String reason) {
        receipts.executeWithoutResult(tx -> db.update("UPDATE et_payment_callback SET last_error=? WHERE id=?"
                + " AND status='RECEIVED' AND recovery_status='AUTO'", bounded(reason), id));
    }

    private void validateAuthentication(CallbackCommand command) {
        if (secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Callback secret is not configured");
        if (!"simulated".equals(command.provider()) || command.eventId() == null || command.eventId().isBlank()
                || command.rawBody() == null || command.signature() == null || command.timestamp() == null || command.status() == null
                || Duration.between(command.timestamp(), Instant.now()).abs().compareTo(freshness) > 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback authentication");
        }
        byte[] expected = hmac(command.timestamp().getEpochSecond() + "." + command.rawBody());
        byte[] supplied;
        try { supplied = HexFormat.of().parseHex(command.signature()); }
        catch (IllegalArgumentException malformed) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback signature"); }
        if (!MessageDigest.isEqual(expected, supplied)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback signature");
    }

    private static void validateBinding(EventPaymentService.PaymentView payment, CallbackView command) {
        if (!"PAYMENT".equals(command.kind()) || !payment.paymentNumber().equals(command.businessNumber())
                || command.providerResultId() == null || command.providerResultId().isBlank()
                || payment.amount() != command.amount()
                || !payment.currency().equals(command.currency())) {
            throw conflict("Callback does not match immutable payment binding");
        }
    }

    private static SimulatedPaymentGateway.GatewayPayment gatewayPayment(CallbackView command) {
        return new SimulatedPaymentGateway.GatewayPayment(command.businessNumber(), command.orderReference(), command.amount(),
                command.currency(), GatewayResultStatus.valueOf(command.resultStatus()), command.providerResultId(), Instant.now(), Instant.now());
    }

    private CallbackView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CallbackView(rs.getLong("id"), rs.getString("provider"), rs.getString("event_id"), rs.getString("kind"),
                rs.getString("business_number"), rs.getString("payload_hash"), rs.getString("provider_result_id"),
                rs.getString("order_reference"), rs.getLong("amount"), rs.getString("currency"), rs.getString("result_status"),
                rs.getString("status"), rs.getString("reason"));
    }

    private byte[] hmac(String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String sha256(String body) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    private static String bounded(String detail) { return detail == null ? null : detail.substring(0, Math.min(255, detail.length())); }
    private static void requireNoTransaction() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Callbacks must start outside a transaction"); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record CallbackCommand(String provider, String eventId, String kind, String businessNumber, String providerResultId,
            String orderReference, long amount, String currency, GatewayResultStatus status, Instant timestamp,
            String rawBody, String signature) {}
    public record CallbackView(long id, String provider, String eventId, String kind, String businessNumber,
            String payloadHash, String providerResultId, String orderReference, long amount, String currency,
            String resultStatus, String status, String reason) {}
}
