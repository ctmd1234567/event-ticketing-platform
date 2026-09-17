package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.payment.EventPaymentService;
import com.eventplatform.payment.EventRefundService;
import com.eventplatform.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {
    private final EventPaymentService payments;
    private final EventRefundService refunds;

    public PaymentController(EventPaymentService payments, EventRefundService refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @PostMapping("/api/v1/orders/{orderId}/payments")
    public Result create(@PathVariable long orderId, @RequestHeader("Idempotency-Key") String key) {
        return Result.ok(payments.create(orderId, actorId(), key));
    }

    @GetMapping("/api/v1/payments/{paymentId}")
    public Result payment(@PathVariable long paymentId) {
        return Result.ok(payments.payment(paymentId, actorId()));
    }

    @PostMapping("/api/v1/payments/{paymentId}/refresh")
    public Result refreshPayment(@PathVariable long paymentId) {
        return Result.ok(payments.refresh(paymentId, actorId()));
    }

    @GetMapping("/api/v1/refunds/{refundId}")
    public Result refund(@PathVariable long refundId) {
        return Result.ok(refunds.refund(refundId, actorId()));
    }

    @PostMapping("/api/v1/refunds/{refundId}/refresh")
    public Result refreshRefund(@PathVariable long refundId) {
        return Result.ok(refunds.refresh(refundId, actorId()));
    }

    private long actorId() {
        return UserHolder.getUser().getId();
    }
}
