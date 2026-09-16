package com.eventplatform.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Default gateway result policy. Tests can replace the interface with a deterministic policy. */
@Component
public class ConfiguredSimulatedGatewayOutcomePolicy implements SimulatedGatewayOutcomePolicy {
    private final GatewayResultStatus paymentStatus;
    private final GatewayResultStatus refundStatus;

    public ConfiguredSimulatedGatewayOutcomePolicy(
            @Value("${app.simulated-gateway.payment-status:SUCCEEDED}") String paymentStatus,
            @Value("${app.simulated-gateway.refund-status:SUCCEEDED}") String refundStatus) {
        this.paymentStatus = GatewayResultStatus.configured(paymentStatus,
                "app.simulated-gateway.payment-status");
        this.refundStatus = GatewayResultStatus.configured(refundStatus,
                "app.simulated-gateway.refund-status");
    }

    @Override
    public GatewayResultStatus paymentOutcome(SimulatedPaymentGateway.PaymentRequest request) {
        return paymentStatus;
    }

    @Override
    public GatewayResultStatus refundOutcome(SimulatedPaymentGateway.RefundRequest request) {
        return refundStatus;
    }
}
