package com.eventplatform.payment;

import com.eventplatform.catalog.EventCatalogService;
import com.eventplatform.order.EventOrderService;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Same behavioral assertions run on isolated H2 and on the real V1–V6 MySQL schema. */
abstract class PaymentServiceContract {
    protected abstract DataSource source() throws Exception;
    private GateJdbc db;
    private EventOrderService orders;
    private EventPaymentService payments;
    private SimulatedPaymentGateway gateway;
    private GatewayResponseFaultInjector faults;
    private TransactionTemplate local;
    private EventOrderService.OrderView order;
    private String key;

    @BeforeEach
    void fixture() throws Exception {
        DataSource source = source();
        db = new GateJdbc(source);
        var manager = new DataSourceTransactionManager(source);
        local = new TransactionTemplate(manager);
        orders = new EventOrderService(db, manager, 900, 30);
        faults = new GatewayResponseFaultInjector("NONE");
        gateway = spy(new JdbcSimulatedPaymentGateway(db, manager,
                new ConfiguredSimulatedGatewayOutcomePolicy("SUCCEEDED", "SUCCEEDED"), faults));
        payments = new EventPaymentService(db, manager, gateway);
        var catalog = new EventCatalogService(db);
        Instant now = Instant.now();
        long event = catalog.createEvent(1, "Payment fixture", null, "Test venue");
        long session = catalog.addSession(event, "Main", now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        long tier = catalog.addTicketTier(session, "One ticket", 4750, "CNY", 1);
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
    void closeDuringGatewayCallRetainsChargeForManualHandlingWithoutReallocating() {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            orders.cancel(order.id(), 7);
            return result;
        }).when(gateway).createPayment(any());
        var late = payments.create(order.id(), 7, key);
        assertThat(late.status()).isEqualTo("SUCCEEDED");
        assertThat(late.recoveryStatus()).isEqualTo("MANUAL_REQUIRED");
        assertThat(late.lastError()).isEqualTo("LATE_PAYMENT_COMPENSATION_NOT_IMPLEMENTED");
        assertThat(orders.order(order.id(), 7).status()).isEqualTo("CLOSED");
        assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?", String.class, order.id()))
                .isEqualTo("RELEASED");
        assertInventory(1, 0, 0);
        payments.applyResult(late.id(), gateway.queryPayment(late.paymentNumber()).orElseThrow());
        assertInventory(1, 0, 0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_refund WHERE payment_id=?", Integer.class, late.id())).isZero();
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
            if (sql.contains("expiry_next_attempt_at")) {
                Runnable hook = afterCandidates.getAndSet(null);
                if (hook != null) hook.run();
            }
            return result;
        }

        @Override
        public int update(String sql, Object... args) {
            if (failResultHistory && sql.contains("INSERT INTO et_payment_history") && "GATEWAY_RESULT".equals(args[2])) {
                throw new IllegalStateException("Injected history persistence failure");
            }
            return super.update(sql, args);
        }
    }
}
