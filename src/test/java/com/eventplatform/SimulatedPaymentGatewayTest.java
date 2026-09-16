package com.eventplatform;

import com.eventplatform.payment.GatewayOperation;
import com.eventplatform.payment.GatewayRequestConflictException;
import com.eventplatform.payment.GatewayResponseFaultInjector;
import com.eventplatform.payment.GatewayResponseLostException;
import com.eventplatform.payment.GatewayResultStatus;
import com.eventplatform.payment.JdbcSimulatedPaymentGateway;
import com.eventplatform.payment.SimulatedGatewayOutcomePolicy;
import com.eventplatform.payment.SimulatedPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedPaymentGatewayTest {
    private JdbcTemplate db;
    private TransactionTemplate localTransactions;
    private GatewayResponseFaultInjector faults;
    private SimulatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:sim_gateway;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("DROP ALL OBJECTS");
        db.execute("""
                CREATE TABLE sim_gateway_payment(
                  payment_number VARCHAR(64) PRIMARY KEY,order_reference VARCHAR(64) NOT NULL,
                  amount BIGINT NOT NULL,currency CHAR(3) NOT NULL,status VARCHAR(16) NOT NULL,
                  provider_transaction_id VARCHAR(64) NOT NULL UNIQUE,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        db.execute("""
                CREATE TABLE sim_gateway_refund(
                  refund_number VARCHAR(64) PRIMARY KEY,payment_number VARCHAR(64) NOT NULL UNIQUE,
                  amount BIGINT NOT NULL,currency CHAR(3) NOT NULL,status VARCHAR(16) NOT NULL,
                  provider_refund_id VARCHAR(64) NOT NULL UNIQUE,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  FOREIGN KEY(payment_number) REFERENCES sim_gateway_payment(payment_number))
                """);
        db.execute("CREATE TABLE local_result_marker(id BIGINT PRIMARY KEY)");
        faults = new GatewayResponseFaultInjector("NONE");
        SimulatedGatewayOutcomePolicy succeeds = new SimulatedGatewayOutcomePolicy() {
            @Override
            public GatewayResultStatus paymentOutcome(SimulatedPaymentGateway.PaymentRequest request) {
                return GatewayResultStatus.SUCCEEDED;
            }

            @Override
            public GatewayResultStatus refundOutcome(SimulatedPaymentGateway.RefundRequest request) {
                return GatewayResultStatus.SUCCEEDED;
            }
        };
        var transactionManager = new DataSourceTransactionManager(source);
        localTransactions = new TransactionTemplate(transactionManager);
        gateway = new JdbcSimulatedPaymentGateway(db, transactionManager, succeeds, faults);
    }

    @Test
    void persistsOneChargeAndOneMatchingRefundPerBusinessNumber() {
        var paymentRequest = new SimulatedPaymentGateway.PaymentRequest("PAY-0001", "EO-0001", 4750, "CNY");
        var payment = gateway.createPayment(paymentRequest);
        var replay = gateway.createPayment(paymentRequest);

        assertThat(payment.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        assertThat(replay.providerTransactionId()).isEqualTo(payment.providerTransactionId());
        assertThat(gateway.queryPayment("PAY-0001")).contains(payment);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_payment", Integer.class)).isOne();
        assertThatThrownBy(() -> gateway.createPayment(
                new SimulatedPaymentGateway.PaymentRequest("PAY-0001", "EO-0001", 4800, "CNY")))
                .isInstanceOf(GatewayRequestConflictException.class);

        var refundRequest = new SimulatedPaymentGateway.RefundRequest("REF-0001", "PAY-0001", 4750, "CNY");
        var refund = gateway.createRefund(refundRequest);
        var refundReplay = gateway.createRefund(refundRequest);

        assertThat(refund.status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        assertThat(refundReplay.providerRefundId()).isEqualTo(refund.providerRefundId());
        assertThat(gateway.queryRefund("REF-0001")).contains(refund);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_refund", Integer.class)).isOne();
        assertThatThrownBy(() -> gateway.createRefund(
                new SimulatedPaymentGateway.RefundRequest("REF-0002", "PAY-0001", 4700, "CNY")))
                .isInstanceOf(GatewayRequestConflictException.class);
    }

    @Test
    void gatewayCommitSurvivesResponseLossAndCallerRollback() {
        faults.loseNextSuccessfulResponse(GatewayOperation.PAYMENT);

        localTransactions.executeWithoutResult(status -> {
            db.update("INSERT INTO local_result_marker(id) VALUES (1)");
            assertThatThrownBy(() -> gateway.createPayment(
                    new SimulatedPaymentGateway.PaymentRequest("PAY-LOST-01", "EO-LOST-01", 4750, "CNY")))
                    .isInstanceOf(GatewayResponseLostException.class);
            assertThat(gateway.queryPayment("PAY-LOST-01")).isPresent();
            status.setRollbackOnly();
        });

        var persisted = gateway.queryPayment("PAY-LOST-01");
        assertThat(persisted).isPresent();
        assertThat(persisted.orElseThrow().status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_payment WHERE payment_number='PAY-LOST-01'",
                Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM local_result_marker", Integer.class)).isZero();
    }

    @Test
    void refundCommitSurvivesResponseLossAndCallerRollback() {
        gateway.createPayment(new SimulatedPaymentGateway.PaymentRequest(
                "PAY-LOST-REFUND", "EO-LOST-REFUND", 4750, "CNY"));
        faults.loseNextSuccessfulResponse(GatewayOperation.REFUND);

        localTransactions.executeWithoutResult(status -> {
            db.update("INSERT INTO local_result_marker(id) VALUES (2)");
            assertThatThrownBy(() -> gateway.createRefund(new SimulatedPaymentGateway.RefundRequest(
                    "REF-LOST-01", "PAY-LOST-REFUND", 4750, "CNY")))
                    .isInstanceOf(GatewayResponseLostException.class);
            assertThat(gateway.queryRefund("REF-LOST-01")).isPresent();
            status.setRollbackOnly();
        });

        var persisted = gateway.queryRefund("REF-LOST-01");
        assertThat(persisted).isPresent();
        assertThat(persisted.orElseThrow().status()).isEqualTo(GatewayResultStatus.SUCCEEDED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sim_gateway_refund WHERE refund_number='REF-LOST-01'",
                Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM local_result_marker", Integer.class)).isZero();
    }
}
