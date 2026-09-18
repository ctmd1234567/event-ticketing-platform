package com.eventplatform.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@org.springframework.context.annotation.Profile("legacy-experiment")
public class OrderPerformance {
    private final Semaphore inFlight;
    private final long waitMillis;
    private final Timer reservation;
    private final Timer completionLag;
    private final Counter rejected;

    public OrderPerformance(MeterRegistry registry,
            @Value("${app.order.max-in-flight:24}") int maxInFlight,
            @Value("${app.order.admission-wait-ms:100}") long waitMillis) {
        this.inFlight = new Semaphore(Math.max(1, maxInFlight), true);
        this.waitMillis = Math.max(0, waitMillis);
        this.reservation = registry.timer("order.reservation.duration");
        this.completionLag = registry.timer("order.completion.lag");
        this.rejected = registry.counter("order.admission.rejected");
        registry.gauge("order.admission.available", inFlight, Semaphore::availablePermits);
    }

    public <T> T protect(Supplier<T> operation) {
        boolean acquired;
        try {
            acquired = inFlight.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            rejected.increment();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Order service is busy; retry shortly");
        }
        try {
            return reservation.record(operation);
        } finally {
            inFlight.release();
        }
    }

    public void completed(Duration elapsed) {
        completionLag.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }
}
