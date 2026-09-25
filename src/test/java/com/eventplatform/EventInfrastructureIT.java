package com.eventplatform;

import com.eventplatform.catalog.EventCatalogService;
import com.eventplatform.order.EventOrderService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "app.event-orders.expiry-scan-enabled=false",
        "app.event-orders.max-in-flight=64",
        "app.payment-recovery.enabled=false",
        "app.event-notifications.enabled=false",
        "spring.rabbitmq.port=1"
})
class EventInfrastructureIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("event_trading");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = mysql.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?") + "serverTimezone=UTC";
        });
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    DataSource source;

    @Autowired
    JdbcTemplate db;

    @Autowired
    EventOrderService eventOrders;

    @Autowired
    EventCatalogService catalog;

    @Autowired
    Flyway flyway;

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test
    void realFlywayMySqlAndEventOrderRoundTrip() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'archive_legacy_%'
                """, Integer.class)).isEqualTo(6);

        new ResourceDatabasePopulator(new ClassPathResource("db/event-infrastructure-seed.sql"))
                .execute(source);

        assertThat(catalog.listEvents())
                .extracting(EventCatalogService.EventView::id)
                .contains(910001L);

        var eventOrder = eventOrders.create(900001, "integration-order-0001", 910001, 1);

        assertThat(eventOrder.unitPrice()).isEqualTo(4750);
        assertThat(eventOrder.currency()).isEqualTo("CNY");
        assertThat(eventOrder.createdAt()).isEqualTo(db.queryForObject(
                "SELECT created_at FROM et_order WHERE id=?", java.sql.Timestamp.class,
                eventOrder.id()).toInstant());
        assertThat(db.queryForMap(
                "SELECT available,reserved,allocated FROM et_ticket_tier WHERE id=910001"))
                .containsEntry("available", 1)
                .containsEntry("reserved", 1)
                .containsEntry("allocated", 0);
    }

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test
    void oneThousandRequestsReserveExactlyOneHundredTickets() throws Exception {
        Instant now = Instant.now();
        long eventId = catalog.createEvent(1, "Concurrency acceptance event", null, "Test venue");
        long sessionId = catalog.addSession(eventId, "Main session",
                now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        long tierId = catalog.addTicketTier(sessionId, "Standard", 4750, "CNY", 100);
        catalog.publish(eventId);

        int requestCount = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> technicalFailures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        List<Future<RequestOutcome>> futures = new ArrayList<>(requestCount);
        try {
            for (int index = 0; index < requestCount; index++) {
                int requestIndex = index;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        eventOrders.create(1_000_000L + requestIndex,
                                "concurrency-order-" + String.format("%04d", requestIndex), tierId, 1);
                        return RequestOutcome.RESERVED;
                    } catch (ResponseStatusException rejection) {
                        if (rejection.getStatusCode() == HttpStatus.CONFLICT) {
                            return RequestOutcome.BUSINESS_REJECTED;
                        }
                        technicalFailures.add(rejection.getStatusCode() + ": " + rejection.getReason());
                        return RequestOutcome.TECHNICAL_FAILED;
                    } catch (Throwable technicalFailure) {
                        technicalFailures.add(technicalFailure.getClass().getName() + ": "
                                + technicalFailure.getMessage());
                        return RequestOutcome.TECHNICAL_FAILED;
                    }
                }));
            }
            start.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            List<RequestOutcome> outcomes = new ArrayList<>(requestCount);
            for (Future<RequestOutcome> future : futures) {
                long remaining = deadline - System.nanoTime();
                assertThat(remaining)
                        .as("all 1,000 requests complete within the test budget")
                        .isPositive();
                outcomes.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }

            assertThat(technicalFailures)
                    .as("technical failures: %s", technicalFailures)
                    .isEmpty();
            assertThat(outcomes).filteredOn(RequestOutcome.RESERVED::equals).hasSize(100);
            assertThat(outcomes).filteredOn(RequestOutcome.BUSINESS_REJECTED::equals).hasSize(900);
            assertThat(outcomes).filteredOn(RequestOutcome.TECHNICAL_FAILED::equals).isEmpty();
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.queryForMap("SELECT capacity,available,reserved,allocated "
                + "FROM et_ticket_tier WHERE id=?", tierId))
                .containsEntry("capacity", 100)
                .containsEntry("available", 0)
                .containsEntry("reserved", 100)
                .containsEntry("allocated", 0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_order WHERE ticket_tier_id=? "
                + "AND status='PENDING_PAYMENT'", Integer.class, tierId)).isEqualTo(100);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_inventory_reservation "
                + "WHERE ticket_tier_id=? AND status='RESERVED'", Integer.class, tierId)).isEqualTo(100);
        assertThat(db.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM et_inventory_reservation "
                + "WHERE ticket_tier_id=? AND status='RESERVED'", Integer.class, tierId)).isEqualTo(100);
    }

    private enum RequestOutcome {
        RESERVED,
        BUSINESS_REJECTED,
        TECHNICAL_FAILED
    }
}
