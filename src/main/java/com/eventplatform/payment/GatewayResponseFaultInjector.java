package com.eventplatform.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A one-shot, post-commit transport fault. It never changes a gateway result;
 * callers must query the persisted simulated gateway record to recover.
 */
@Component
public class GatewayResponseFaultInjector {
    private final AtomicReference<GatewayOperation> loseOnce;

    public GatewayResponseFaultInjector(
            @Value("${app.simulated-gateway.fault-after-commit-once:NONE}") String configuredOperation) {
        this.loseOnce = new AtomicReference<>(operation(configuredOperation));
    }

    public void afterSuccessfulCommit(GatewayOperation operation, String businessNumber) {
        if (loseOnce.compareAndSet(operation, GatewayOperation.NONE)) {
            throw new GatewayResponseLostException(operation, businessNumber);
        }
    }

    /** A deterministic test seam; it only affects the next successful result of this operation. */
    public void loseNextSuccessfulResponse(GatewayOperation operation) {
        if (operation == null || operation == GatewayOperation.NONE) {
            throw new IllegalArgumentException("Only PAYMENT or REFUND can lose a simulated response");
        }
        if (!loseOnce.compareAndSet(GatewayOperation.NONE, operation)) {
            throw new IllegalStateException("A simulated response-loss fault is already armed");
        }
    }

    private GatewayOperation operation(String value) {
        try {
            return GatewayOperation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "app.simulated-gateway.fault-after-commit-once must be NONE, PAYMENT, or REFUND", invalid);
        }
    }
}
