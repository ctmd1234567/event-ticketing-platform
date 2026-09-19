package com.eventplatform;

import com.eventplatform.catalog.EventCatalogService;
import com.eventplatform.order.EventOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class EventOrderLifecycleIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("event_trading_lifecycle");

    @Test
    void closesWithinBudgetAndRecoversAfterRestart() throws Exception {
        ConfigurableApplicationContext first = startApplication(true);
        List<Long> restartOrderIds = new ArrayList<>();
        long restartTierId;
        Instant restartDeadline;
        try {
            EventCatalogService catalog = first.getBean(EventCatalogService.class);
            EventOrderService orders = first.getBean(EventOrderService.class);
            JdbcTemplate db = first.getBean(JdbcTemplate.class);

            long healthyTierId = createSellableTier(catalog, "healthy", 1);
            var healthyOrder = orders.create(700001, "lifecycle-healthy-0001", healthyTierId, 1);
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> assertClosedAndReleased(db, healthyOrder.id(),
                            healthyTierId, 1, "PAYMENT_EXPIRED"));
            assertThat(Instant.now()).isBeforeOrEqualTo(healthyOrder.paymentDeadline().plusSeconds(10));
            assertThat(orders.closeExpiredBatch(10)).isZero();

            restartTierId = createSellableTier(catalog, "restart", 4);
            for (int index = 0; index < 4; index++) {
                var order = orders.create(710000 + index, "lifecycle-restart-" + index + "-0001",
                        restartTierId, 1);
                restartOrderIds.add(order.id());
            }
            restartDeadline = orders.order(restartOrderIds.getFirst(), 710000).paymentDeadline();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_order WHERE ticket_tier_id=? "
                    + "AND status='PENDING_PAYMENT'", Integer.class, restartTierId)).isEqualTo(4);
        } finally {
            first.close();
        }

        await().atMost(Duration.ofSeconds(35)).pollInterval(Duration.ofMillis(100))
                .until(() -> !Instant.now().isBefore(restartDeadline));

        Instant restartedAt = Instant.now();
        try (ConfigurableApplicationContext second = startApplication(true)) {
            JdbcTemplate db = second.getBean(JdbcTemplate.class);
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_order "
                                + "WHERE ticket_tier_id=? AND status='CLOSED' "
                                + "AND close_reason='PAYMENT_EXPIRED'", Integer.class, restartTierId))
                                .isEqualTo(4);
                        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_inventory_reservation "
                                + "WHERE ticket_tier_id=? AND status='RELEASED'",
                                Integer.class, restartTierId)).isEqualTo(4);
                        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_inventory_reservation "
                                + "WHERE ticket_tier_id=? AND status='RESERVED'",
                                Integer.class, restartTierId)).isZero();
                    });
            assertThat(Duration.between(restartedAt, Instant.now())).isLessThan(Duration.ofSeconds(30));
            assertInventory(db, restartTierId, 4, 4, 0, 0);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_order o "
                    + "JOIN et_inventory_reservation r ON r.order_id=o.id "
                    + "WHERE o.ticket_tier_id=? AND o.status='CLOSED' AND r.status='RELEASED'",
                    Integer.class, restartTierId)).isEqualTo(restartOrderIds.size());
        }
    }

    @Test
    void concurrentCancelExpiryAndCreateCloseConserveInventory() throws Exception {
        try (ConfigurableApplicationContext context = startApplication(false)) {
            EventCatalogService catalog = context.getBean(EventCatalogService.class);
            EventOrderService orders = context.getBean(EventOrderService.class);
            JdbcTemplate db = context.getBean(JdbcTemplate.class);

            long raceTierId = createSellableTier(catalog, "race", 1);
            var raceOrder = orders.create(700002, "lifecycle-race-000001", raceTierId, 1);
            db.update("UPDATE et_order SET payment_deadline=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) "
                    + "WHERE id=?", raceOrder.id());
            CountDownLatch raceStart = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var cancel = pool.submit(() -> {
                    raceStart.await();
                    return orders.cancel(raceOrder.id(), 700002);
                });
                var expire = pool.submit(() -> {
                    raceStart.await();
                    return orders.closeExpiredBatch(10);
                });
                raceStart.countDown();
                cancel.get(10, TimeUnit.SECONDS);
                expire.get(10, TimeUnit.SECONDS);
            }
            String closeReason = db.queryForObject(
                    "SELECT close_reason FROM et_order WHERE id=?", String.class, raceOrder.id());
            assertThat(closeReason).isIn("USER_CANCELED", "PAYMENT_EXPIRED");
            assertClosedAndReleased(db, raceOrder.id(), raceTierId, 1, closeReason);

            long sharedTierId = createSellableTier(catalog, "create-close", 2);
            var expiringOrder = orders.create(
                    700003, "lifecycle-create-close-01", sharedTierId, 1);
            db.update("UPDATE et_order SET payment_deadline=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) "
                    + "WHERE id=?", expiringOrder.id());
            CountDownLatch createCloseStart = new CountDownLatch(1);
            EventOrderService.OrderView created;
            try (var pool = Executors.newFixedThreadPool(2)) {
                var close = pool.submit(() -> {
                    createCloseStart.await();
                    return orders.closeExpiredBatch(10);
                });
                var create = pool.submit(() -> {
                    createCloseStart.await();
                    return orders.create(700004, "lifecycle-create-close-02", sharedTierId, 1);
                });
                createCloseStart.countDown();
                close.get(10, TimeUnit.SECONDS);
                created = create.get(10, TimeUnit.SECONDS);
            }
            assertThat(orders.order(expiringOrder.id(), 700003).status()).isEqualTo("CLOSED");
            assertThat(orders.order(expiringOrder.id(), 700003).closeReason())
                    .isEqualTo("PAYMENT_EXPIRED");
            assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?",
                    String.class, expiringOrder.id())).isEqualTo("RELEASED");
            assertThat(created.status()).isEqualTo("PENDING_PAYMENT");
            assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?",
                    String.class, created.id())).isEqualTo("RESERVED");
            assertInventory(db, sharedTierId, 2, 1, 1, 0);
        }
    }

    private ConfigurableApplicationContext startApplication(boolean expiryScanEnabled) {
        String jdbcUrl = mysql.getJdbcUrl()
                + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "serverTimezone=UTC";
        return new SpringApplicationBuilder(EventTradingPlatformApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=" + mysql.getUsername(),
                        "--spring.datasource.password=" + mysql.getPassword(),
                        "--app.event-orders.payment-window-seconds=30",
                        "--app.event-orders.expiry-scan-interval-ms=1000",
                        "--app.event-orders.expiry-scan-batch-size=100",
                        "--app.event-orders.expiry-scan-enabled=" + expiryScanEnabled,
                        "--logging.level.com.eventplatform=INFO");
    }

    private long createSellableTier(EventCatalogService catalog, String suffix, int capacity) {
        Instant now = Instant.now();
        long eventId = catalog.createEvent(1, "Lifecycle " + suffix, null, "Test venue");
        long sessionId = catalog.addSession(eventId, "Session " + suffix,
                now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        long tierId = catalog.addTicketTier(sessionId, "Tier " + suffix, 4750, "CNY", capacity);
        catalog.publish(eventId);
        return tierId;
    }

    private void assertClosedAndReleased(JdbcTemplate db, long orderId, long tierId,
            int capacity, String closeReason) {
        assertThat(db.queryForObject("SELECT status FROM et_order WHERE id=?",
                String.class, orderId)).isEqualTo("CLOSED");
        assertThat(db.queryForObject("SELECT close_reason FROM et_order WHERE id=?",
                String.class, orderId)).isEqualTo(closeReason);
        assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?",
                String.class, orderId)).isEqualTo("RELEASED");
        assertInventory(db, tierId, capacity, capacity, 0, 0);
    }

    private void assertInventory(JdbcTemplate db, long tierId, int capacity,
            int available, int reserved, int allocated) {
        assertThat(db.queryForMap("SELECT capacity,available,reserved,allocated "
                + "FROM et_ticket_tier WHERE id=?", tierId))
                .containsEntry("capacity", capacity)
                .containsEntry("available", available)
                .containsEntry("reserved", reserved)
                .containsEntry("allocated", allocated);
        assertThat(available + reserved + allocated).isEqualTo(capacity);
    }
}
