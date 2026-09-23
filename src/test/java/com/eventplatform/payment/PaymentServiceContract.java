package com.eventplatform.payment;

import com.eventplatform.catalog.EventCatalogService;
import com.eventplatform.order.EventOrderService;
import com.eventplatform.notification.EventNotificationOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Same behavioral assertions run on isolated H2 and on the real V1–V6 MySQL schema. */
abstract class PaymentServiceContract {
    protected abstract DataSource source() throws Exception;
    private GateJdbc db;
    private EventOrderService orders;
    private EventNotificationOutbox notificationOutbox;
    private EventPaymentService payments;
    private EventRefundService refunds;
    private PaymentCallbackService callbacks;
    private SimulatedPaymentGateway gateway;
    private GatewayResponseFaultInjector faults;
    private TransactionTemplate local;
    private DataSourceTransactionManager manager;
    private EventOrderService.OrderView order;
    private EventCatalogService catalog;
    private long tier;
    private String key;

    @BeforeEach
    void fixture() throws Exception {
        DataSource source = source();
        db = new GateJdbc(source);
        manager = new DataSourceTransactionManager(source);
        local = new TransactionTemplate(manager);
        notificationOutbox = new EventNotificationOutbox(db, new ObjectMapper());
        orders = new EventOrderService(db, manager, notificationOutbox, 900, 30);
        faults = new GatewayResponseFaultInjector("NONE");
        gateway = spy(new JdbcSimulatedPaymentGateway(db, manager,
                new ConfiguredSimulatedGatewayOutcomePolicy("SUCCEEDED", "SUCCEEDED"), faults));
        payments = new EventPaymentService(db, manager, gateway, notificationOutbox);
        refunds = new EventRefundService(db, manager, gateway);
        callbacks = new PaymentCallbackService(db, manager, payments, refunds, "callback-test-secret", 300);
        catalog = new EventCatalogService(db);
        Instant now = Instant.now();
        long event = catalog.createEvent(1, "Payment fixture", null, "Test venue");
        long session = catalog.addSession(event, "Main", now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        tier = catalog.addTicketTier(session, "One ticket", 4750, "CNY", 1);
        catalog.publish(event);
        order = orders.create(7, UUID.randomUUID().toString(), tier, 1);
        key = UUID.randomUUID().toString();
    }

    @Test
    void normalPaymentCommitsTogetherAndReplaysWithoutAnotherGatewayCall() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(db.queryForObject("SELECT status FROM et_payment WHERE order_id=?", String.class, order.id()))
                    .isEqualTo("PROCESSING");
            return call.callRealMethod();
        }).when(gateway).createPayment(any());
        var paid = payments.create(order.id(), 7, key);
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        assertThat(paid.amount()).isEqualTo(4750);
        assertThat(paid.currency()).isEqualTo("CNY");
        assertPaid();
        assertThat(payments.create(order.id(), 7, key)).isEqualTo(paid);
        payments.applyResult(paid.id(), gateway.queryPayment(paid.paymentNumber()).orElseThrow());
        payments.markUnknown(paid.id(), 7, "delayed transport error");
        assertThat(payments.payment(paid.id(), 7).status()).isEqualTo("SUCCEEDED");
        assertPaid();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_history WHERE payment_id=?", Integer.class, paid.id()))
                .isEqualTo(2);
        verify(gateway, times(1)).createPayment(any());
    }

    @Test
    void rejectsWrongOwnerKeyStateDeadlineAndAmbientTransaction() {
        assertStatus(() -> payments.create(order.id(), 8, key), HttpStatus.NOT_FOUND);
        assertStatus(() -> payments.create(order.id(), 7, "short"), HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> local.executeWithoutResult(tx -> payments.create(order.id(), 7, key)))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE et_order SET payment_deadline=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(60)), order.id());
        assertStatus(() -> payments.create(order.id(), 7, key), HttpStatus.CONFLICT);
        orders.cancel(order.id(), 7);
        assertStatus(() -> payments.create(order.id(), 7, key), HttpStatus.CONFLICT);
        verify(gateway, never()).createPayment(any());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment WHERE order_id=?", Integer.class, order.id())).isZero();
    }

    @Test
    void rejectsDifferentKeyAndHidesOwnedPayment() {
        var paid = payments.create(order.id(), 7, key);
        assertStatus(() -> payments.create(order.id(), 7, UUID.randomUUID().toString()), HttpStatus.CONFLICT);
        assertStatus(() -> payments.payment(paid.id(), 8), HttpStatus.NOT_FOUND);
        assertStatus(() -> orders.cancel(order.id(), 7), HttpStatus.CONFLICT);
        assertPaid();
    }

    @Test
    void lostResponsePersistsUnknownAndSameKeyDoesNotDispatchAgain() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(gateway.queryPayment(unknown.paymentNumber()).orElseThrow().status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        assertThat(payments.create(order.id(), 7, key)).isEqualTo(unknown);
        assertPending();
        verify(gateway, times(1)).createPayment(any());
    }

    @Test
    void trustedFailedAndProcessingResultsDoNotAllocate() {
        doAnswer(call -> {
            SimulatedPaymentGateway.PaymentRequest r = call.getArgument(0);
            return new SimulatedPaymentGateway.GatewayPayment(r.paymentNumber(), r.orderReference(), r.amount(), r.currency(),
                    GatewayResultStatus.PROCESSING, "processing-" + order.id(), Instant.now(), Instant.now());
        }).when(gateway).createPayment(any());
        var processing = payments.create(order.id(), 7, key);
        assertThat(processing.status()).isEqualTo("PROCESSING");
        assertPending();
        var rejected = new SimulatedPaymentGateway.GatewayPayment(processing.paymentNumber(), order.orderNumber(),
                4750, "CNY", GatewayResultStatus.FAILED, processing.providerTransactionId(), Instant.now(), Instant.now());
        assertThat(payments.applyResult(processing.id(), rejected).status()).isEqualTo("FAILED");
        Instant callbackTime = Instant.now();
        var failedCallback = callback("event-confirm-failed", rejected, callbackTime, "{\"event\":\"failed\"}");
        assertThat(callbacks.receive(failedCallback).status()).isEqualTo("APPLIED");
        payments.markUnknown(processing.id(), 7, "stale timeout");
        assertThat(payments.payment(processing.id(), 7).status()).isEqualTo("FAILED");
        assertPending();
    }

    @Test
    void rejectsMismatchedTrustedResultWithoutPartialChanges() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var payment = payments.create(order.id(), 7, key);
        var result = gateway.queryPayment(payment.paymentNumber()).orElseThrow();
        var bad = new SimulatedPaymentGateway.GatewayPayment(result.paymentNumber(), result.orderReference(),
                result.amount() + 1, result.currency(), result.status(), result.providerTransactionId(), result.createdAt(), result.updatedAt());
        assertStatus(() -> payments.applyResult(payment.id(), bad), HttpStatus.CONFLICT);
        assertThat(payments.payment(payment.id(), 7).status()).isEqualTo("UNKNOWN");
        assertPending();
    }

    @Test
    void historyFailureRollsBackWholeLocalConfirmationButNotGateway() {
        db.failResultHistory = true;
        assertThatThrownBy(() -> payments.create(order.id(), 7, key)).isInstanceOf(IllegalStateException.class);
        String number = db.queryForObject("SELECT payment_number FROM et_payment WHERE order_id=?", String.class, order.id());
        assertThat(gateway.queryPayment(number).orElseThrow().status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        assertThat(db.queryForObject("SELECT status FROM et_payment WHERE order_id=?", String.class, order.id())).isEqualTo("PROCESSING");
        assertPending();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_history h JOIN et_payment p ON p.id=h.payment_id WHERE p.order_id=?",
                Integer.class, order.id())).isOne();
    }

    @Test
    void paymentWinsAgainstAlreadySelectedExpiryCandidate() throws Exception {
        CountDownLatch paymentLocked = new CountDownLatch(1);
        CountDownLatch releasePayment = new CountDownLatch(1);
        CountDownLatch expirySelected = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            db.update("UPDATE et_order SET payment_deadline=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(60)), order.id());
            db.afterOrderLock.set(() -> { paymentLocked.countDown(); await(releasePayment); });
            return result;
        }).when(gateway).createPayment(any());
        db.afterCandidates.set(expirySelected::countDown);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var pay = pool.submit(() -> payments.create(order.id(), 7, key));
            await(paymentLocked); // Real SELECT FOR UPDATE has returned and still holds the lock.
            var expire = pool.submit(() -> orders.closeExpiredBatch(1000));
            await(expirySelected); // Candidate selected before payment commits.
            releasePayment.countDown();
            assertThat(pay.get(10, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
            assertThat(expire.get(10, TimeUnit.SECONDS)).isZero();
            assertPaid();
            assertThat(db.queryForObject("SELECT expiry_attempts FROM et_order WHERE id=?", Integer.class, order.id())).isZero();
        } finally {
            releasePayment.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void expiryWinsBeforeLateGatewaySuccessAndCreatesCompensation() {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            db.update("UPDATE et_order SET payment_deadline=? WHERE id=?",
                    Timestamp.from(Instant.now().minusSeconds(60)), order.id());
            assertThat(orders.closeExpiredBatch(100)).isOne();
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        assertThat(late.status()).isEqualTo("SUCCEEDED");
        assertThat(orders.order(order.id(), 7).status()).isEqualTo("CLOSED");
        assertThat(orders.order(order.id(), 7).closeReason()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_refund WHERE payment_id=?", Integer.class, late.id())).isOne();
        assertInventory(1, 0, 0);
    }

    @Test
    void concurrentSameKeyReturnsOnePaymentAndDispatchesOnce() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { dispatched.countDown(); await(release); return call.callRealMethod(); })
                .when(gateway).createPayment(any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> payments.create(order.id(), 7, key));
            await(dispatched);
            var replay = payments.create(order.id(), 7, key);
            assertThat(replay.status()).isEqualTo("PROCESSING");
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).id()).isEqualTo(replay.id());
            assertPaid();
            verify(gateway, times(1)).createPayment(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void closeDuringGatewayCallCreatesAndCompletesOneCompensationWithoutReallocating() {
        AtomicReference<EventOrderService.OrderView> replacement = new AtomicReference<>();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            orders.cancel(order.id(), 7);
            replacement.set(orders.create(8, UUID.randomUUID().toString(), tier, 1));
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        assertThat(late.status()).isEqualTo("SUCCEEDED");
        assertThat(late.recoveryStatus()).isEqualTo("NONE");
        assertThat(late.lastError()).isNull();
        assertThat(orders.order(order.id(), 7).status()).isEqualTo("CLOSED");
        assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?", String.class, order.id()))
                .isEqualTo("RELEASED");
        assertThat(orders.order(replacement.get().id(), 8).status()).isEqualTo("PENDING_PAYMENT");
        assertInventory(0, 1, 0);
        payments.applyResult(late.id(), gateway.queryPayment(late.paymentNumber()).orElseThrow());
        assertInventory(0, 1, 0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_refund WHERE payment_id=?", Integer.class, late.id())).isOne();
        long refundId = db.queryForObject("SELECT id FROM et_refund WHERE payment_id=?", Long.class, late.id());
        db.update("UPDATE et_refund SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), refundId);
        var claim = refunds.claimDueRecoveries(100).stream().filter(c -> c.refundId() == refundId).findFirst().orElseThrow();
        assertThat(refunds.recoverClaim(claim).status()).isEqualTo("SUCCEEDED");
        assertStatus(() -> refunds.refund(refundId, 8), HttpStatus.NOT_FOUND);
        assertInventory(0, 1, 0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_refund WHERE payment_number=?", Integer.class,
                late.paymentNumber())).isOne();
    }

    @Test
    void cancellationWinsWhileCommittedGatewayResultIsDeterministicallyDelayed() throws Exception {
        CountDownLatch gatewayCommitted = new CountDownLatch(1);
        CountDownLatch releaseResult = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            gatewayCommitted.countDown();
            await(releaseResult);
            return result;
        }).when(gateway).createPayment(any());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pay = pool.submit(() -> payments.create(order.id(), 7, key));
            await(gatewayCommitted);
            assertThat(orders.cancel(order.id(), 7).status()).isEqualTo("CLOSED");
            assertInventory(1, 0, 0);
            releaseResult.countDown();
            var late = pay.get(10, TimeUnit.SECONDS);
            assertThat(late.status()).isEqualTo("SUCCEEDED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_refund WHERE payment_id=?", Integer.class,
                    late.id())).isOne();
            assertThat(orders.order(order.id(), 7).status()).isEqualTo("CLOSED");
            assertInventory(1, 0, 0);
        } finally {
            releaseResult.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lostRefundResponseBecomesUnknownThenQueryCompletesSameRefund() {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            orders.cancel(order.id(), 7);
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        long refundId = db.queryForObject("SELECT id FROM et_refund WHERE payment_id=?", Long.class, late.id());
        faults.loseNextSuccessfulResponse(GatewayOperation.REFUND);
        db.update("UPDATE et_refund SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), refundId);
        var first = refunds.claimDueRecoveries(100).stream().filter(c -> c.refundId() == refundId).findFirst().orElseThrow();
        assertThat(refunds.recoverClaim(first).status()).isEqualTo("UNKNOWN");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_refund WHERE payment_number=?", Integer.class,
                late.paymentNumber())).isOne();
        db.update("UPDATE et_refund SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), refundId);
        var second = refunds.claimDueRecoveries(100).stream().filter(c -> c.refundId() == refundId).findFirst().orElseThrow();
        assertThat(refunds.recoverClaim(second).status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_refund WHERE payment_number=?", Integer.class,
                late.paymentNumber())).isOne();
        assertInventory(1, 0, 0);
    }

    @Test
    void signedRefundCallbackAppliesOnceAndCannotChangeInventory() {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            orders.cancel(order.id(), 7);
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        long refundId = db.queryForObject("SELECT id FROM et_refund WHERE payment_id=?", Long.class, late.id());
        String refundNumber = db.queryForObject("SELECT refund_number FROM et_refund WHERE id=?", String.class, refundId);
        var gatewayRefund = gateway.createRefund(new SimulatedPaymentGateway.RefundRequest(refundNumber,
                late.paymentNumber(), late.amount(), late.currency()));
        Instant timestamp = Instant.now();
        String raw = "{\"event\":\"refund-success\"}";
        var command = refundCallback("refund-event-1", gatewayRefund, order.orderNumber(), timestamp, raw);
        assertThat(callbacks.receive(command).status()).isEqualTo("APPLIED");
        assertThat(callbacks.receive(command).status()).isEqualTo("APPLIED");
        assertThat(refunds.refund(refundId, 7).status()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_refund WHERE refund_number=?", Integer.class,
                refundNumber)).isOne();
        assertInventory(1, 0, 0);
    }

    @Test
    void explicitRefundFailureIsVisibleAndAuditedManualRetryReusesTheNumber() {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            orders.cancel(order.id(), 7);
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        long refundId = db.queryForObject("SELECT id FROM et_refund WHERE payment_id=?", Long.class, late.id());
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            SimulatedPaymentGateway.GatewayRefund result = (SimulatedPaymentGateway.GatewayRefund) call.callRealMethod();
            if (!first.getAndSet(false)) return result;
            db.update("UPDATE sim_gateway_refund SET status='FAILED' WHERE refund_number=?", result.refundNumber());
            return new SimulatedPaymentGateway.GatewayRefund(result.refundNumber(), result.paymentNumber(), result.amount(),
                    result.currency(), GatewayResultStatus.FAILED, result.providerRefundId(), result.createdAt(), Instant.now());
        }).when(gateway).createRefund(any());
        db.update("UPDATE et_refund SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), refundId);
        var claim = refunds.claimDueRecoveries(100).stream().filter(c -> c.refundId() == refundId).findFirst().orElseThrow();
        var failed = refunds.recoverClaim(claim);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.recoveryStatus()).isEqualTo("MANUAL_REQUIRED");
        String operationKey = UUID.randomUUID().toString();
        var recovered = refunds.manualRetry(refundId, 1, operationKey, "Provider issue resolved");
        assertThat(recovered.status()).isEqualTo("SUCCEEDED");
        assertThat(refunds.manualRetry(refundId, 1, operationKey, "Provider issue resolved")).isEqualTo(recovered);
        assertStatus(() -> refunds.manualRetry(refundId, 1, operationKey, "Different request"), HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_refund WHERE refund_number=?", Integer.class,
                recovered.refundNumber())).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_history WHERE refund_id=? AND action='REFUND_MANUAL_RETRY'",
                Integer.class, refundId)).isOne();
        verify(gateway, times(2)).createRefund(any());
        assertInventory(1, 0, 0);
    }

    @Test
    void explicitPaymentFailureCanOnlyBeRetriedWithOneAuditedOperation() {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            SimulatedPaymentGateway.GatewayPayment result = (SimulatedPaymentGateway.GatewayPayment) call.callRealMethod();
            if (!first.getAndSet(false)) return result;
            db.update("UPDATE sim_gateway_payment SET status='FAILED' WHERE payment_number=?", result.paymentNumber());
            return new SimulatedPaymentGateway.GatewayPayment(result.paymentNumber(), result.orderReference(),
                    result.amount(), result.currency(), GatewayResultStatus.FAILED, result.providerTransactionId(),
                    result.createdAt(), Instant.now());
        }).when(gateway).createPayment(any());
        var failed = payments.create(order.id(), 7, key);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.recoveryStatus()).isEqualTo("MANUAL_REQUIRED");
        assertPending();
        String operationKey = UUID.randomUUID().toString();
        var paid = payments.manualRetry(failed.id(), 1, operationKey, "Retry confirmed failed charge");
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        assertThat(payments.manualRetry(failed.id(), 1, operationKey, "Retry confirmed failed charge")).isEqualTo(paid);
        assertStatus(() -> payments.manualRetry(failed.id(), 1, operationKey, "Different request"), HttpStatus.CONFLICT);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_payment WHERE payment_number=?", Integer.class,
                paid.paymentNumber())).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_history WHERE payment_id=? AND action='PAYMENT_MANUAL_RETRY'",
                Integer.class, paid.id())).isOne();
        verify(gateway, times(2)).createPayment(any());
        assertPaid();
    }

    @Test
    void manualRecoveryNeverStartsANewChargeAfterClosure() {
        doThrow(new IllegalStateException("gateway unavailable")).when(gateway).createPayment(any());
        var unknown = payments.create(order.id(), 7, key);
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(orders.cancel(order.id(), 7).status()).isEqualTo("CLOSED");
        db.update("UPDATE et_payment SET recovery_status='MANUAL_REQUIRED',next_attempt_at=NULL WHERE id=?", unknown.id());

        var unchanged = payments.manualRetry(unknown.id(), 1, UUID.randomUUID().toString(),
                "Gateway has no matching transaction");

        assertThat(unchanged.status()).isEqualTo("UNKNOWN");
        assertThat(unchanged.recoveryStatus()).isEqualTo("MANUAL_REQUIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_payment WHERE payment_number=?", Integer.class,
                unknown.paymentNumber())).isZero();
        verify(gateway, times(1)).createPayment(any());
        assertThat(orders.order(order.id(), 7).status()).isEqualTo("CLOSED");
        assertInventory(1, 0, 0);
    }

    @Test
    void exhaustedRefundRecoveryBecomesQueryableManualWork() {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            orders.cancel(order.id(), 7);
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        long refundId = db.queryForObject("SELECT id FROM et_refund WHERE payment_id=?", Long.class, late.id());
        db.update("UPDATE et_refund SET attempts=5,next_attempt_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), refundId);
        assertThat(refunds.claimDueRecoveries(100)).isEmpty();
        var manual = refunds.refund(refundId, 7);
        assertThat(manual.status()).isEqualTo("REQUESTED");
        assertThat(manual.recoveryStatus()).isEqualTo("MANUAL_REQUIRED");
        assertThat(manual.lastError()).isEqualTo("REFUND_ATTEMPTS_EXHAUSTED");
        verify(gateway, never()).createRefund(any());
    }

    @Test
    void concurrentIdenticalCallbacksProduceOneReceiptAndOneAllocation() throws Exception {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        var result = gateway.queryPayment(unknown.paymentNumber()).orElseThrow();
        Instant timestamp = Instant.now();
        var command = callback("event-concurrent", result, timestamp, "{\"event\":\"same\"}");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { ready.countDown(); await(start); return callbacks.receive(command); });
            var second = pool.submit(() -> { ready.countDown(); await(start); return callbacks.receive(command); });
            await(ready);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("APPLIED");
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo("APPLIED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_callback WHERE event_id='event-concurrent'",
                    Integer.class)).isOne();
            assertPaid();
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void signedCallbackHasDurableIdempotentReceiptAndAppliesTrustedGatewaySuccess() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        var gatewayResult = gateway.queryPayment(unknown.paymentNumber()).orElseThrow();
        Instant timestamp = Instant.now();
        String raw = "{\"event\":\"callback-success\"}";
        var command = callback("event-callback-1", gatewayResult, timestamp, raw);
        var invalid = new PaymentCallbackService.CallbackCommand(command.provider(), "event-invalid-signature",
                command.kind(), command.businessNumber(), command.providerResultId(), command.orderReference(),
                command.amount(), command.currency(), command.status(), command.timestamp(), command.rawBody(), "00");
        assertStatus(() -> callbacks.receive(invalid), HttpStatus.UNAUTHORIZED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_callback WHERE event_id=?", Integer.class,
                "event-invalid-signature")).isZero();
        var applied = callbacks.receive(command);
        assertThat(applied.status()).isEqualTo("APPLIED");
        assertPaid();
        assertThat(callbacks.receive(command)).isEqualTo(applied);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_history WHERE payment_id=?", Integer.class, unknown.id()))
                .isEqualTo(3);
        var changedPayload = callback("event-callback-1", gatewayResult, timestamp, "{\"event\":\"different\"}");
        assertStatus(() -> callbacks.receive(changedPayload), HttpStatus.CONFLICT);
    }

    @Test
    void authenticatedMismatchedAndUnknownCallbacksRemainAuditableWithoutBusinessEffects() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        var result = gateway.queryPayment(unknown.paymentNumber()).orElseThrow();
        Instant now = Instant.now();
        String mismatchRaw = "{\"event\":\"mismatch\"}";
        var mismatch = new PaymentCallbackService.CallbackCommand("simulated", "event-mismatch", "PAYMENT",
                result.paymentNumber(), result.providerTransactionId(), "OTHER-ORDER", result.amount(), result.currency(),
                result.status(), now, mismatchRaw, sign("callback-test-secret", now, mismatchRaw));
        assertThat(callbacks.receive(mismatch).status()).isEqualTo("REJECTED");
        assertPending();

        String unmatchedRaw = "{\"event\":\"unmatched\"}";
        var unmatched = new PaymentCallbackService.CallbackCommand("simulated", "event-unmatched", "PAYMENT",
                "EP-ABSENT", "GP-ABSENT", "EO-ABSENT", 4750, "CNY", GatewayResultStatus.SUCCEEDED,
                now, unmatchedRaw, sign("callback-test-secret", now, unmatchedRaw));
        var stored = callbacks.receive(unmatched);
        assertThat(stored.status()).isEqualTo("UNMATCHED");
        assertThat(db.queryForObject("SELECT recovery_status FROM et_payment_callback WHERE id=?", String.class,
                stored.id())).isEqualTo("MANUAL_REQUIRED");
        assertPending();

        Instant stale = now.minusSeconds(301);
        String staleRaw = "{\"event\":\"stale\"}";
        var staleCommand = new PaymentCallbackService.CallbackCommand("simulated", "event-stale", "PAYMENT",
                result.paymentNumber(), result.providerTransactionId(), result.orderReference(), result.amount(),
                result.currency(), result.status(), stale, staleRaw, sign("callback-test-secret", stale, staleRaw));
        assertStatus(() -> callbacks.receive(staleCommand), HttpStatus.UNAUTHORIZED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_payment_callback WHERE event_id='event-stale'",
                Integer.class)).isZero();
    }

    @Test
    void recoveryClaimsQueryPersistedGatewayAndHandsOffExhaustedUncertainty() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        db.update("UPDATE et_payment SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), unknown.id());
        var claims = payments.claimDueRecoveries(100);
        var claim = claims.stream().filter(candidate -> candidate.paymentId() == unknown.id()).findFirst().orElseThrow();
        assertThat(payments.recoverClaim(claim).status()).isEqualTo("SUCCEEDED");
        assertPaid();

        // This direct row setup isolates the bounded-handoff transition; it deliberately does not invoke result application.
        db.update("UPDATE et_payment SET status='UNKNOWN',recovery_status='AUTO',attempts=5,next_attempt_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), unknown.id());
        assertThat(payments.claimDueRecoveries(100)).isEmpty();
        assertThat(payments.payment(unknown.id(), 7).recoveryStatus()).isEqualTo("MANUAL_REQUIRED");
    }

    @Test
    void reconstructedStartupScannerRecoversPersistedUnknownPayment() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        db.update("UPDATE et_payment SET next_attempt_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), unknown.id());
        var reconstructedPayments = new EventPaymentService(db, manager, gateway, notificationOutbox);
        var reconstructedRefunds = new EventRefundService(db, manager, gateway);
        var reconstructedCallbacks = new PaymentCallbackService(db, manager, reconstructedPayments,
                reconstructedRefunds, "callback-test-secret", 300);
        var scanner = new PaymentRecoveryScanner(reconstructedPayments, reconstructedRefunds,
                reconstructedCallbacks, 100);
        scanner.recoverAfterRestart();
        assertThat(reconstructedPayments.payment(unknown.id(), 7).status()).isEqualTo("SUCCEEDED");
        assertPaid();
    }

    @Test
    void receivedCallbackIsRecoveredAfterItsBusinessTransactionRollsBack() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        var result = gateway.queryPayment(unknown.paymentNumber()).orElseThrow();
        Instant timestamp = Instant.now();
        var command = callback("event-receipt-recovery", result, timestamp, "{\"event\":\"recover\"}");
        db.failResultHistory = true;
        assertThatThrownBy(() -> callbacks.receive(command)).isInstanceOf(IllegalStateException.class);
        long callbackId = db.queryForObject("SELECT id FROM et_payment_callback WHERE event_id=?", Long.class,
                "event-receipt-recovery");
        assertThat(callbacks.callback(callbackId).status()).isEqualTo("RECEIVED");
        assertPending();
        db.failResultHistory = false;
        db.update("UPDATE et_payment_callback SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), callbackId);
        callbacks.recoverDueReceipts(100);
        assertThat(callbacks.callback(callbackId).status()).isEqualTo("APPLIED");
        assertPaid();
    }

    @Test
    void concurrentRecoveryScannersCannotClaimTheSamePaymentTwice() throws Exception {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);
        var unknown = payments.create(order.id(), 7, key);
        db.update("UPDATE et_payment SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), unknown.id());
        CountDownLatch bothSelected = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        db.afterRecoveryCandidates.set(() -> { bothSelected.countDown(); await(release); });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> payments.claimDueRecoveries(100));
            var second = pool.submit(() -> payments.claimDueRecoveries(100));
            await(bothSelected);
            release.countDown();
            long targetClaims = java.util.stream.Stream.concat(first.get(10, TimeUnit.SECONDS).stream(),
                    second.get(10, TimeUnit.SECONDS).stream()).filter(c -> c.paymentId() == unknown.id()).count();
            assertThat(targetClaims).isOne();
            assertThat(db.queryForObject("SELECT attempts FROM et_payment WHERE id=?", Integer.class, unknown.id())).isEqualTo(2);
        } finally {
            release.countDown();
            db.afterRecoveryCandidates.set(null);
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRefundScannersCannotClaimTheSameIntentTwice() throws Exception {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            orders.cancel(order.id(), 7);
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        long refundId = db.queryForObject("SELECT id FROM et_refund WHERE payment_id=?", Long.class, late.id());
        db.update("UPDATE et_refund SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), refundId);
        CountDownLatch bothSelected = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        db.afterRefundRecoveryCandidates.set(() -> { bothSelected.countDown(); await(release); });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> refunds.claimDueRecoveries(100));
            var second = pool.submit(() -> refunds.claimDueRecoveries(100));
            await(bothSelected);
            release.countDown();
            long targetClaims = java.util.stream.Stream.concat(first.get(10, TimeUnit.SECONDS).stream(),
                    second.get(10, TimeUnit.SECONDS).stream()).filter(c -> c.refundId() == refundId).count();
            assertThat(targetClaims).isOne();
            assertThat(db.queryForObject("SELECT attempts FROM et_refund WHERE id=?", Integer.class, refundId)).isOne();
        } finally {
            release.countDown();
            db.afterRefundRecoveryCandidates.set(null);
            pool.shutdownNow();
        }
    }

    private static PaymentCallbackService.CallbackCommand callback(String eventId,
            SimulatedPaymentGateway.GatewayPayment result, Instant timestamp, String rawBody) {
        return new PaymentCallbackService.CallbackCommand("simulated", eventId, "PAYMENT", result.paymentNumber(),
                result.providerTransactionId(), result.orderReference(), result.amount(), result.currency(), result.status(),
                timestamp, rawBody, sign("callback-test-secret", timestamp, rawBody));
    }

    private static PaymentCallbackService.CallbackCommand refundCallback(String eventId,
            SimulatedPaymentGateway.GatewayRefund result, String orderReference, Instant timestamp, String rawBody) {
        return new PaymentCallbackService.CallbackCommand("simulated", eventId, "REFUND", result.refundNumber(),
                result.providerRefundId(), orderReference, result.amount(), result.currency(), result.status(),
                timestamp, rawBody, sign("callback-test-secret", timestamp, rawBody));
    }

    private static String sign(String secret, Instant timestamp, String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp.getEpochSecond() + "." + rawBody)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void assertPaid() {
        assertThat(orders.order(order.id(), 7).status()).isEqualTo("PAID");
        assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?", String.class, order.id()))
                .isEqualTo("CONFIRMED");
        assertInventory(0, 0, 1);
    }

    private void assertPending() {
        assertThat(orders.order(order.id(), 7).status()).isEqualTo("PENDING_PAYMENT");
        assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?", String.class, order.id()))
                .isEqualTo("RESERVED");
        assertInventory(0, 1, 0);
    }

    private void assertInventory(int available, int reserved, int allocated) {
        assertThat(db.queryForMap("SELECT capacity,available,reserved,allocated FROM et_ticket_tier WHERE id=?", order.ticketTierId()))
                .containsEntry("capacity", 1).containsEntry("available", available)
                .containsEntry("reserved", reserved).containsEntry("allocated", allocated);
    }

    private static void assertStatus(Runnable command, HttpStatus status) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** Test-only hooks execute after real JDBC calls, while the real transaction retains its locks. */
    private static final class GateJdbc extends JdbcTemplate {
        final AtomicReference<Runnable> afterOrderLock = new AtomicReference<>();
        final AtomicReference<Runnable> afterCandidates = new AtomicReference<>();
        final AtomicReference<Runnable> afterRecoveryCandidates = new AtomicReference<>();
        final AtomicReference<Runnable> afterRefundRecoveryCandidates = new AtomicReference<>();
        boolean failResultHistory;

        GateJdbc(DataSource source) { super(source); }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
            T result = super.queryForObject(sql, mapper, args);
            if (sql.contains("FROM et_order WHERE id=? FOR UPDATE")) {
                Runnable hook = afterOrderLock.getAndSet(null);
                if (hook != null) hook.run();
            }
            return result;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> type) {
            List<T> result = super.queryForList(sql, type);
            afterCandidateQuery(sql);
            return result;
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            List<T> result = super.queryForList(sql, type, args);
            afterCandidateQuery(sql);
            return result;
        }

        private void afterCandidateQuery(String sql) {
            if (sql.contains("expiry_next_attempt_at")) {
                Runnable hook = afterCandidates.getAndSet(null);
                if (hook != null) hook.run();
            }
            if (sql.contains("FROM et_payment") && sql.contains("recovery_status='AUTO'")) {
                Runnable hook = afterRecoveryCandidates.get();
                if (hook != null) hook.run();
            }
            if (sql.contains("FROM et_refund") && sql.contains("recovery_status='AUTO'")) {
                Runnable hook = afterRefundRecoveryCandidates.get();
                if (hook != null) hook.run();
            }
        }

        @Override
        public int update(String sql, Object... args) {
            if (failResultHistory && sql.contains("INSERT INTO et_payment_history")
                    && ("GATEWAY_RESULT".equals(args[3]) || "CALLBACK_RESULT".equals(args[3]))) {
                throw new IllegalStateException("Injected history persistence failure");
            }
            return super.update(sql, args);
        }
    }
}
