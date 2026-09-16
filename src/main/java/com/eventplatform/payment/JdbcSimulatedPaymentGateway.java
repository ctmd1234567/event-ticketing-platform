package com.eventplatform.payment;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * The gateway has no foreign keys to local order/payment records and commits each
 * provider operation in a new transaction. Its result may therefore outlive a
 * caller transaction or a lost response.
 */
@Service
public class JdbcSimulatedPaymentGateway implements SimulatedPaymentGateway {
    private final JdbcTemplate db;
    private final TransactionTemplate gatewayTransactions;
    private final SimulatedGatewayOutcomePolicy outcomes;
    private final GatewayResponseFaultInjector responseFaults;

    public JdbcSimulatedPaymentGateway(JdbcTemplate db, PlatformTransactionManager transactionManager,
            SimulatedGatewayOutcomePolicy outcomes, GatewayResponseFaultInjector responseFaults) {
        this.db = db;
        this.outcomes = outcomes;
        this.responseFaults = responseFaults;
        this.gatewayTransactions = new TransactionTemplate(transactionManager);
        this.gatewayTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public GatewayPayment createPayment(PaymentRequest request) {
        GatewayPayment result;
        try {
            result = required(gatewayTransactions.execute(status -> createPaymentInGateway(request)));
        } catch (DuplicateKeyException concurrentCreate) {
            result = queryPayment(request.paymentNumber()).orElseThrow(() -> concurrentCreate);
            assertSamePayment(result, request);
        }
        if (result.status() == GatewayResultStatus.SUCCEEDED) {
            responseFaults.afterSuccessfulCommit(GatewayOperation.PAYMENT, request.paymentNumber());
        }
        return result;
    }

    @Override
    public Optional<GatewayPayment> queryPayment(String paymentNumber) {
        return required(gatewayTransactions.execute(status -> findPayment(paymentNumber)));
    }

    private Optional<GatewayPayment> findPayment(String paymentNumber) {
        return db.query("""
                SELECT payment_number,order_reference,amount,currency,status,provider_transaction_id,
                       created_at,updated_at
                FROM sim_gateway_payment WHERE payment_number=?
                """, (rs, row) -> mapPayment(rs), paymentNumber).stream().findFirst();
    }

    @Override
    public GatewayRefund createRefund(RefundRequest request) {
        GatewayRefund result;
        try {
            result = required(gatewayTransactions.execute(status -> createRefundInGateway(request)));
        } catch (DuplicateKeyException concurrentCreate) {
            Optional<GatewayRefund> sameBusinessNumber = queryRefund(request.refundNumber());
            if (sameBusinessNumber.isPresent()) {
                result = sameBusinessNumber.get();
                assertSameRefund(result, request);
            } else {
                throw new GatewayRequestConflictException(
                        "A full gateway refund already exists for this payment");
            }
        }
        if (result.status() == GatewayResultStatus.SUCCEEDED) {
            responseFaults.afterSuccessfulCommit(GatewayOperation.REFUND, request.refundNumber());
        }
        return result;
    }

    @Override
    public Optional<GatewayRefund> queryRefund(String refundNumber) {
        return required(gatewayTransactions.execute(status -> findRefund(refundNumber)));
    }

    private Optional<GatewayRefund> findRefund(String refundNumber) {
        return db.query("""
                SELECT refund_number,payment_number,amount,currency,status,provider_refund_id,
                       created_at,updated_at
                FROM sim_gateway_refund WHERE refund_number=?
                """, (rs, row) -> mapRefund(rs), refundNumber).stream().findFirst();
    }

    private GatewayPayment createPaymentInGateway(PaymentRequest request) {
        Optional<GatewayPayment> existing = findPayment(request.paymentNumber());
        if (existing.isPresent()) {
            GatewayPayment replay = existing.get();
            assertSamePayment(replay, request);
            return replay;
        }
        GatewayResultStatus outcome = outcomes.paymentOutcome(request);
        // Business numbers are already provider-unique opaque IDs. Reusing them
        // avoids length-changing derivations and keeps the result lookup stable.
        String providerTransactionId = request.paymentNumber();
        db.update("""
                INSERT INTO sim_gateway_payment(
                    payment_number,order_reference,amount,currency,status,provider_transaction_id)
                VALUES (?,?,?,?,?,?)
                """, request.paymentNumber(), request.orderReference(), request.amount(), request.currency(),
                outcome.name(), providerTransactionId);
        return findPayment(request.paymentNumber()).orElseThrow(
                () -> new IllegalStateException("Gateway payment was not readable after its commit"));
    }

    private GatewayRefund createRefundInGateway(RefundRequest request) {
        Optional<GatewayRefund> existing = findRefund(request.refundNumber());
        if (existing.isPresent()) {
            GatewayRefund replay = existing.get();
            assertSameRefund(replay, request);
            return replay;
        }
        GatewayPayment payment = findPayment(request.paymentNumber()).orElseThrow(
                () -> new GatewayRequestConflictException("Gateway payment does not exist"));
        if (payment.status() != GatewayResultStatus.SUCCEEDED) {
            throw new GatewayRequestConflictException("Only a successful gateway payment can be refunded");
        }
        if (payment.amount() != request.amount() || !payment.currency().equals(request.currency())) {
            throw new GatewayRequestConflictException("Gateway refund must match the successful payment amount and currency");
        }
        GatewayResultStatus outcome = outcomes.refundOutcome(request);
        String providerRefundId = request.refundNumber();
        db.update("""
                INSERT INTO sim_gateway_refund(
                    refund_number,payment_number,amount,currency,status,provider_refund_id)
                VALUES (?,?,?,?,?,?)
                """, request.refundNumber(), request.paymentNumber(), request.amount(), request.currency(),
                outcome.name(), providerRefundId);
        return findRefund(request.refundNumber()).orElseThrow(
                () -> new IllegalStateException("Gateway refund was not readable after its commit"));
    }

    private void assertSamePayment(GatewayPayment payment, PaymentRequest request) {
        if (!payment.orderReference().equals(request.orderReference()) || payment.amount() != request.amount()
                || !payment.currency().equals(request.currency())) {
            throw new GatewayRequestConflictException("Gateway payment number was reused with a different request");
        }
    }

    private void assertSameRefund(GatewayRefund refund, RefundRequest request) {
        if (!refund.paymentNumber().equals(request.paymentNumber()) || refund.amount() != request.amount()
                || !refund.currency().equals(request.currency())) {
            throw new GatewayRequestConflictException("Gateway refund number was reused with a different request");
        }
    }

    private GatewayPayment mapPayment(ResultSet rs) throws SQLException {
        return new GatewayPayment(rs.getString("payment_number"), rs.getString("order_reference"),
                rs.getLong("amount"), rs.getString("currency"),
                GatewayResultStatus.valueOf(rs.getString("status")), rs.getString("provider_transaction_id"),
                timestamp(rs, "created_at"), timestamp(rs, "updated_at"));
    }

    private GatewayRefund mapRefund(ResultSet rs) throws SQLException {
        return new GatewayRefund(rs.getString("refund_number"), rs.getString("payment_number"),
                rs.getLong("amount"), rs.getString("currency"),
                GatewayResultStatus.valueOf(rs.getString("status")), rs.getString("provider_refund_id"),
                timestamp(rs, "created_at"), timestamp(rs, "updated_at"));
    }

    private Instant timestamp(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private <T> T required(T value) {
        if (value == null) {
            throw new IllegalStateException("Gateway transaction returned no result");
        }
        return value;
    }
}
