package com.eventplatform;

import com.eventplatform.order.OrderTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ActiveProfiles({"local", "legacy-experiment"})
@SpringBootTest(properties = {
        "app.event-orders.expiry-scan-enabled=false",
        "app.payment-recovery.enabled=false",
        "app.event-notifications.enabled=false"
})
class LegacyMessagingIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("event_trading");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = mysql.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?") + "serverTimezone=UTC";
        });
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    DataSource source;

    @Autowired
    JdbcTemplate db;

    @Autowired
    OrderTransactions orders;

    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @Test
    void realRedisRabbitMqAndLegacyVoucherRoundTrip() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/legacy-messaging-seed.sql"))
                .execute(source);

        var ping = redis.execInContainer("redis-cli", "PING");
        assertThat(ping.getExitCode()).isZero();
        assertThat(ping.getStdout()).contains("PONG");

        long id = orders.reserve(900001, 900001);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(orders.status(id, 900001))
                        .containsEntry("state", "COMPLETED"));

        orders.fulfill(id);

        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE id=?", Integer.class, id))
                .isEqualTo(1);
        assertThat(db.queryForObject(
                "SELECT SUM(stock) FROM tb_seckill_voucher_bucket WHERE voucher_id=900001",
                Integer.class))
                .isEqualTo(1);
    }
}
