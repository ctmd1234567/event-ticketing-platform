package com.eventplatform;

import com.eventplatform.order.OrderTransactions;
import com.eventplatform.order.EventOrderService;
import com.eventplatform.catalog.EventCatalogService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest
class InfrastructureIT {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4").withDatabaseName("event_trading");
    @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379).waitingFor(Wait.forListeningPort());
    @Container static RabbitMQContainer rabbit=new RabbitMQContainer("rabbitmq:4.1-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> {
            String url = mysql.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?") + "serverTimezone=UTC";
        });
        r.add("spring.datasource.username",mysql::getUsername);
        r.add("spring.datasource.password",mysql::getPassword);
        r.add("spring.data.redis.host",redis::getHost);
        r.add("spring.data.redis.port",() -> redis.getMappedPort(6379));
        r.add("spring.rabbitmq.host",rabbit::getHost);
        r.add("spring.rabbitmq.port",rabbit::getAmqpPort);
        r.add("spring.rabbitmq.username",rabbit::getAdminUsername);
        r.add("spring.rabbitmq.password",rabbit::getAdminPassword);
    }
    @Autowired DataSource source;
    @Autowired JdbcTemplate db;
    @Autowired OrderTransactions orders;
    @Autowired EventOrderService eventOrders;
    @Autowired EventCatalogService catalog;
    @Autowired Flyway flyway;
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test void realFlywayMySqlRedisAndBrokerRoundTrip() throws Exception {
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().getVersion())
            .containsExactly("1", "2", "3", "4", "5", "6");
        new ResourceDatabasePopulator(new ClassPathResource("db/infrastructure-seed.sql")).execute(source);
        assertThat(catalog.listEvents()).extracting(EventCatalogService.EventView::id).contains(910001L);
        var eventOrder = eventOrders.create(900001, "integration-order-0001", 910001, 1);
        assertThat(eventOrder.unitPrice()).isEqualTo(4750);
        assertThat(eventOrder.currency()).isEqualTo("CNY");
        assertThat(db.queryForMap("SELECT available,reserved,allocated FROM et_ticket_tier WHERE id=910001"))
                .containsEntry("available", 1).containsEntry("reserved", 1).containsEntry("allocated", 0);
        var ping = redis.execInContainer("redis-cli", "PING");
        assertThat(ping.getExitCode()).isZero();
        assertThat(ping.getStdout()).contains("PONG");
        long id=orders.reserve(900001,900001);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(orders.status(id,900001)).containsEntry("state","COMPLETED"));
        orders.fulfill(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT SUM(stock) FROM tb_seckill_voucher_bucket WHERE voucher_id=900001",Integer.class)).isEqualTo(1);
    }

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test void oneThousandRequestsReserveExactlyOneHundredTickets() throws Exception {
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
                assertThat(remaining).as("all 1,000 requests complete within the test budget").isPositive();
                outcomes.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }

            assertThat(technicalFailures).as("technical failures: %s", technicalFailures).isEmpty();
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
