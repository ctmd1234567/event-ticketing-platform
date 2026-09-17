package com.eventplatform.controller;

import com.eventplatform.dto.Result;
import com.eventplatform.payment.EventPaymentService;
import com.eventplatform.payment.EventRefundService;
import com.eventplatform.payment.PaymentRecoveryAdminService;
import com.eventplatform.utils.UserHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class PaymentRecoveryAdminController {
    private final EventPaymentService payments;
    private final EventRefundService refunds;
    private final PaymentRecoveryAdminService recovery;

    public PaymentRecoveryAdminController(EventPaymentService payments, EventRefundService refunds,
            PaymentRecoveryAdminService recovery) {
        this.payments = payments;
        this.refunds = refunds;
        this.recovery = recovery;
    }

    @GetMapping("/payment-recovery")
    public Result recovery(@RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") long afterId) {
        return Result.ok(recovery.items(limit, afterId));
    }

    @PostMapping("/payments/{paymentId}/retry")
    public Result retryPayment(@PathVariable long paymentId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RetryRequest request) {
        return Result.ok(payments.manualRetry(paymentId, actorId(), key, request.reason()));
    }

    @PostMapping("/refunds/{refundId}/retry")
    public Result retryRefund(@PathVariable long refundId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RetryRequest request) {
        return Result.ok(refunds.manualRetry(refundId, actorId(), key, request.reason()));
    }

    private long actorId() {
        return UserHolder.getUser().getId();
    }

    public record RetryRequest(@NotBlank @Size(max = 255) String reason) {}
}
