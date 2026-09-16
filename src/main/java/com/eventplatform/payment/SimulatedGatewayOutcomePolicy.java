package com.eventplatform.payment;

public interface SimulatedGatewayOutcomePolicy {
    GatewayResultStatus paymentOutcome(SimulatedPaymentGateway.PaymentRequest request);

    GatewayResultStatus refundOutcome(SimulatedPaymentGateway.RefundRequest request);
}
