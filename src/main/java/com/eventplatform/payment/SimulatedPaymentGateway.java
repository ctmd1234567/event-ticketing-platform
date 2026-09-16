package com.eventplatform.payment;

import java.time.Instant;
import java.util.Optional;

/** Independent persisted simulation of the provider's charge and refund outcomes. */
public interface SimulatedPaymentGateway {
    GatewayPayment createPayment(PaymentRequest request);

    Optional<GatewayPayment> queryPayment(String paymentNumber);

    GatewayRefund createRefund(RefundRequest request);

    Optional<GatewayRefund> queryRefund(String refundNumber);

    record PaymentRequest(String paymentNumber, String orderReference, long amount, String currency) {
        public PaymentRequest {
            validateBusinessNumber(paymentNumber, "payment number");
            validateBusinessNumber(orderReference, "order reference");
            validateMoney(amount, currency);
        }
    }

    record RefundRequest(String refundNumber, String paymentNumber, long amount, String currency) {
        public RefundRequest {
            validateBusinessNumber(refundNumber, "refund number");
            validateBusinessNumber(paymentNumber, "payment number");
            validateMoney(amount, currency);
        }
    }

    record GatewayPayment(String paymentNumber, String orderReference, long amount, String currency,
            GatewayResultStatus status, String providerTransactionId, Instant createdAt, Instant updatedAt) {}

    record GatewayRefund(String refundNumber, String paymentNumber, long amount, String currency,
            GatewayResultStatus status, String providerRefundId, Instant createdAt, Instant updatedAt) {}

    private static void validateBusinessNumber(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 64
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalArgumentException(field + " must contain 1 to 64 visible ASCII characters");
        }
    }

    private static void validateMoney(long amount, String currency) {
        if (amount < 0 || !"CNY".equals(currency)) {
            throw new IllegalArgumentException("Gateway supports nonnegative CNY integer-fen amounts only");
        }
    }
}
