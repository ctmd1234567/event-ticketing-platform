package com.eventplatform.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persistent query recovery for local PROCESSING/UNKNOWN payments; it never submits a new charge. */
@Component
@ConditionalOnProperty(name = "app.payment-recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecoveryScanner {
    private final EventPaymentService payments;
    private final PaymentCallbackService callbacks;
    private final int batchSize;
    public PaymentRecoveryScanner(EventPaymentService payments, PaymentCallbackService callbacks,
            @Value("${app.payment-recovery.batch-size:100}") int batchSize) {
        this.payments = payments; this.callbacks = callbacks; this.batchSize = Math.max(1, batchSize);
    }
    @EventListener(ApplicationReadyEvent.class) public void recoverAfterRestart() { scan(); }
    @Scheduled(fixedDelayString = "${app.payment-recovery.interval-ms:1000}") public void scan() {
        for (EventPaymentService.RecoveryClaim claim : payments.claimDueRecoveries(batchSize)) payments.recoverClaim(claim);
        callbacks.recoverDueReceipts(batchSize);
    }
}
