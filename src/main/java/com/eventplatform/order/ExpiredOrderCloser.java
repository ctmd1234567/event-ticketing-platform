package com.eventplatform.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.event-orders.expiry-scan-enabled", havingValue = "true",
        matchIfMissing = true)
public class ExpiredOrderCloser {
    private static final Logger log = LoggerFactory.getLogger(ExpiredOrderCloser.class);

    private final EventOrderService orders;
    private final int batchSize;

    public ExpiredOrderCloser(EventOrderService orders,
            @Value("${app.event-orders.expiry-scan-batch-size:200}") int batchSize) {
        this.orders = orders;
        this.batchSize = Math.max(1, batchSize);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        closeExpiredOrders();
    }

    @Scheduled(fixedDelayString = "${app.event-orders.expiry-scan-interval-ms:1000}")
    public void closeExpiredOrders() {
        int closed = orders.closeExpiredBatch(batchSize);
        if (closed > 0) {
            log.info("Closed {} expired event orders and released their inventory", closed);
        }
    }
}
