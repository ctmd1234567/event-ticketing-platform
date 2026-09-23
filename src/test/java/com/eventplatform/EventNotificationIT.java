package com.eventplatform;

import com.eventplatform.catalog.EventCatalogService;
import com.eventplatform.notification.EventNotificationConsumer;
import com.eventplatform.notification.EventNotificationPublisher;
import com.eventplatform.notification.EventNotificationQueueConfig;
import com.eventplatform.notification.EventNotificationService;
import com.eventplatform.notification.EventNotificationRedriveService;
import com.eventplatform.order.EventOrderService;
import com.eventplatform.payment.EventPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "app.event-notifications.enabled=true",
        "app.event-notifications.scan-enabled=false",
        "app.event-orders.expiry-scan-enabled=false",
        "app.payment-recovery.enabled=false"
})
class EventNotificationIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("event_notification_acceptance");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "serverTimezone=UTC");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate transactions;
    @Autowired EventCatalogService catalog;
    @Autowired EventOrderService orders;
    @Autowired EventPaymentService payments;
    @Autowired EventNotificationPublisher publisher;
    @Autowired EventNotificationConsumer consumer;
    @Autowired EventNotificationService notifications;
    @Autowired EventNotificationRedriveService redrive;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin admin;
    @Autowired DirectExchange eventNotificationExchange;
    @Autowired Queue eventNotificationQueue;
    @Autowired Binding eventNotificationBinding;

    @Test
    void paidAndClosedEventsDeliverOnceAndRecoverFromFaults() throws Exception {
        long tier = tier();
        var paidOrder = orders.create(70, "notification-paid-" + UUID.randomUUID(), tier, 1);
        var paid = payments.create(paidOrder.id(), 70, "notification-payment-" + UUID.randomUUID());
        assertThat(paid.status()).isEqualTo("SUCCEEDED");
        String paidEvent = "ORDER_PAID:" + paidOrder.id();
        assertThat(status(paidEvent)).isEqualTo("PENDING");
        assertThat(notifications.list(70)).isEmpty();

        // A broker/topology failure leaves a durable intent with a delayed retry.
        AmqpAdmin unavailable = mock(AmqpAdmin.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(unavailable).declareExchange(eventNotificationExchange);
        new EventNotificationPublisher(db, rabbitTemplate, unavailable, eventNotificationExchange,
                eventNotificationQueue, eventNotificationBinding, 50, 8, 30, 5).publish();
        assertThat(status(paidEvent)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                Integer.class, paidEvent)).isEqualTo(1);
        assertThat(notifications.list(70)).isEmpty();

        db.update("UPDATE et_outbox_event SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)"
                + " WHERE event_id=?", paidEvent);
        // Removing the intended binding must be repaired before an ACK can mark PUBLISHED.
        admin.removeBinding(eventNotificationBinding);
        publisher.publish();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status(paidEvent)).isEqualTo("PUBLISHED");
            assertThat(notifications.list(70)).extracting(EventNotificationService.NotificationView::eventId)
                    .containsExactly(paidEvent);
        });
        consumer.receive(paidEvent);
        rabbitTemplate.convertAndSend(EventNotificationQueueConfig.EXCHANGE,
                EventNotificationQueueConfig.ROUTING_KEY, paidEvent);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, paidEvent)).isEqualTo(1));
        assertThat(notifications.list(71)).isEmpty();
        // Model a crash after broker acceptance but before the PUBLISHED update.
        db.update("""
                UPDATE et_outbox_event SET publish_status='PROCESSING',attempts=1,
                    lease_token='crash-after-broker',lease_until=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)
                WHERE event_id=?
                """, paidEvent);
        publisher.publish();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(status(paidEvent)).isEqualTo("PUBLISHED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?",
                    Integer.class, paidEvent)).isEqualTo(1);
        });

        long secondTier = tier();
        var closedOrder = orders.create(71, "notification-close-" + UUID.randomUUID(), secondTier, 1);
        orders.cancel(closedOrder.id(), 71);
        String closedEvent = "ORDER_CLOSED:" + closedOrder.id();
        assertThat(status(closedEvent)).isEqualTo("PENDING");
        db.update("""
                UPDATE et_outbox_event SET publish_status='PROCESSING',attempts=1,
                    lease_token='interrupted-owner',lease_until=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)
                WHERE event_id=?
                """, closedEvent);
        publisher.publish();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status(closedEvent)).isEqualTo("PUBLISHED");
            assertThat(notifications.list(71)).extracting(EventNotificationService.NotificationView::eventId)
                    .containsExactly(closedEvent);
        });
        orders.cancel(closedOrder.id(), 71);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_outbox_event WHERE event_id=?",
                Integer.class, closedEvent)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?",
                Integer.class, closedEvent)).isEqualTo(1);
    }

    @Test
    void confirmReturnTimeoutAndAttemptLimitRemainRecoverable() {
        var order = orders.create(72, "notification-fault-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(order.id(), 72);
        String eventId = "ORDER_CLOSED:" + order.id();
        RabbitTemplate fake = mock(RabbitTemplate.class);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(4);
            correlation.setReturned(new ReturnedMessage(
                    new Message(new byte[0], new MessageProperties()), 312, "NO_ROUTE",
                    EventNotificationQueueConfig.EXCHANGE, EventNotificationQueueConfig.ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(fake).convertAndSend(eq(EventNotificationQueueConfig.EXCHANGE),
                eq(EventNotificationQueueConfig.ROUTING_KEY), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        new EventNotificationPublisher(db, fake, admin, eventNotificationExchange,
                eventNotificationQueue, eventNotificationBinding, 50, 8, 30, 1).publish();
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);

        RabbitTemplate uncertain = mock(RabbitTemplate.class);
        db.update("UPDATE et_outbox_event SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)"
                + " WHERE event_id=?", eventId);
        new EventNotificationPublisher(db, uncertain, admin, eventNotificationExchange,
                eventNotificationQueue, eventNotificationBinding, 50, 8, 30, 1).publish();
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(2);

        // The final unsuccessful claim stops automatic attempts. An operator may
        // reset the same eventId after fixing the route or broker.
        db.update("UPDATE et_outbox_event SET attempts=7,next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)"
                + " WHERE event_id=?", eventId);
        new EventNotificationPublisher(db, fake, admin, eventNotificationExchange,
                eventNotificationQueue, eventNotificationBinding, 50, 8, 30, 1).publish();
        assertThat(status(eventId)).isEqualTo("MANUAL_REQUIRED");
        assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(8);
        db.update("""
                UPDATE et_outbox_event SET publish_status='PENDING',attempts=0,
                    next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND),
                    lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND publish_status='MANUAL_REQUIRED'
                """, eventId);
        publisher.publish();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status(eventId)).isEqualTo("PUBLISHED");
            assertThat(notifications.list(72)).extracting(EventNotificationService.NotificationView::eventId)
                    .containsExactly(eventId);
        });
    }

    @Test
    void outboxWriteFailureRollsBackCloseAndInventoryRelease() {
        long tier = tier();
        var order = orders.create(73, "notification-rollback-" + UUID.randomUUID(), tier, 1);
        String eventId = "ORDER_CLOSED:" + order.id();
        db.update("""
                INSERT INTO et_outbox_event(event_id,event_type,aggregate_id,user_id,payload)
                VALUES (?,?,?,?,?)
                """, eventId, "ORDER_CLOSED", order.id(), 73,
                "{\"orderId\":" + order.id() + ",\"userId\":73,\"reason\":\"fixture\"}");

        assertThatThrownBy(() -> orders.cancel(order.id(), 73)).isInstanceOf(RuntimeException.class);
        assertThat(orders.order(order.id(), 73).status()).isEqualTo("PENDING_PAYMENT");
        assertThat(db.queryForMap("SELECT available,reserved,allocated FROM et_ticket_tier WHERE id=?", tier))
                .containsEntry("available", 0).containsEntry("reserved", 1).containsEntry("allocated", 0);
        assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?",
                String.class, order.id())).isEqualTo("RESERVED");
    }

    @Test
    void eachClaimAndConfirmCorrelationBelongsToOneSend() {
        var first = orders.create(74, "notification-batch-" + UUID.randomUUID(), tier(), 1);
        var second = orders.create(75, "notification-batch-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(first.id(), 74);
        orders.cancel(second.id(), 75);
        String firstEvent = "ORDER_CLOSED:" + first.id();
        String secondEvent = "ORDER_CLOSED:" + second.id();
        List<String> correlations = new ArrayList<>();
        RabbitTemplate fake = mock(RabbitTemplate.class);
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(4);
            correlations.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(fake).convertAndSend(eq(EventNotificationQueueConfig.EXCHANGE),
                eq(EventNotificationQueueConfig.ROUTING_KEY), anyString(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        new EventNotificationPublisher(db, fake, admin, eventNotificationExchange,
                eventNotificationQueue, eventNotificationBinding, 2, 8, 1, 5).publish();

        assertThat(status(firstEvent)).isEqualTo("PUBLISHED");
        assertThat(status(secondEvent)).isEqualTo("PUBLISHED");
        assertThat(correlations).hasSize(2).doesNotHaveDuplicates();
        assertThat(correlations).doesNotContain(firstEvent, secondEvent);
        assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                Integer.class, firstEvent)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                Integer.class, secondEvent)).isEqualTo(1);
    }

    @Test
    void brokerStoppedForOneHundredEventsThenRecoveredWithinBudget() throws Exception {
        Instant now = Instant.now();
        long event = catalog.createEvent(1, "Broker outage acceptance", null, "Test venue");
        long session = catalog.addSession(event, "Main", now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        long tier = catalog.addTicketTier(session, "Standard", 4750, "CNY", 100);
        catalog.publish(event);
        List<String> ids = new ArrayList<>();
        assertThat(rabbit.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            for (int i = 0; i < 100; i++) {
                var order = orders.create(8100 + i, "broker-down-" + UUID.randomUUID(), tier, 1);
                orders.cancel(order.id(), 8100 + i);
                ids.add("ORDER_CLOSED:" + order.id());
            }
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_outbox_event WHERE event_id IN ("
                    + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                    Integer.class, ids.toArray())).isEqualTo(100);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id IN ("
                    + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                    Integer.class, ids.toArray())).isZero();
            publisher.publish();
        } finally {
            assertThat(rabbit.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            publisher.publish();
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_outbox_event WHERE publish_status='PUBLISHED'"
                    + " AND event_id IN (" + placeholders + ")", Integer.class, ids.toArray())).isEqualTo(100);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id IN ("
                    + placeholders + ")", Integer.class, ids.toArray())).isEqualTo(100);
        });
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT event_id) FROM et_notification WHERE event_id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                Integer.class, ids.toArray())).isEqualTo(100);
    }

    @Test
    void poisonMessageIsIsolatedAndAdminRedriveKeepsOriginalEventId() {
        var order = orders.create(76, "notification-poison-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(order.id(), 76);
        String eventId = "ORDER_CLOSED:" + order.id();
        String original = db.queryForObject("SELECT payload FROM et_outbox_event WHERE event_id=?",
                String.class, eventId);
        db.update("UPDATE et_outbox_event SET payload=? WHERE event_id=?",
                "{\"orderId\":0,\"userId\":76}", eventId);
        publisher.publish();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(db.queryForList("SELECT failure_kind FROM et_notification_failure WHERE event_id=?",
                    String.class, eventId)).containsExactly("UNPROCESSABLE");
            assertThat(admin.getQueueInfo(EventNotificationQueueConfig.DLQ).getMessageCount()).isGreaterThanOrEqualTo(1);
        });
        assertThat(db.queryForMap("SELECT failure_reason,failure_count,status,original_body"
                + " FROM et_notification_failure WHERE event_id=?", eventId))
                .containsEntry("failure_count", 1).containsEntry("status", "FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?",
                Integer.class, eventId)).isZero();
        db.update("UPDATE et_outbox_event SET payload=? WHERE event_id=?", original, eventId);
        var result = redrive.redrive(eventId, 9001, "Corrected payload");
        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, eventId)).isEqualTo(1));
        assertThat(db.queryForMap("SELECT event_id,actor_id,reason,action,outcome FROM et_notification_redrive WHERE id=?",
                result.operationId())).containsEntry("event_id", eventId)
                .containsEntry("actor_id", 9001L).containsEntry("action", "REDRIVE_CONSUMER")
                .containsEntry("outcome", "CONFIRMED");
    }

    @Test
    void consumerRollbackAndLostAckRedeliveryHaveOneEffect() throws Exception {
        var order = orders.create(77, "notification-consumer-crash-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(order.id(), 77);
        String eventId = "ORDER_CLOSED:" + order.id();
        // A process dying before commit rolls back the notification write.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            try {
                consumer.receive(eventId);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            throw new IllegalStateException("injected crash before commit");
        })).hasMessageContaining("injected crash before commit");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?",
                Integer.class, eventId)).isZero();

        rabbitTemplate.convertAndSend(EventNotificationQueueConfig.EXCHANGE,
                EventNotificationQueueConfig.ROUTING_KEY, eventId);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, eventId)).isEqualTo(1));

        // Exercise the duplicate handler synchronously, so the assertion cannot
        // pass before the duplicate is actually processed.
        consumer.receive(eventId);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);
        // A second Broker delivery is also allowed; its arrival is asynchronous.
        rabbitTemplate.convertAndSend(EventNotificationQueueConfig.EXCHANGE,
                EventNotificationQueueConfig.ROUTING_KEY, eventId);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, eventId)).isEqualTo(1));
    }

    @Test
    void distinctOversizedPoisonBodiesKeepSeparateFailureRecords() throws Exception {
        String prefix = "malformed:" + "x".repeat(80);
        byte[] first = (prefix + ":first").getBytes(StandardCharsets.UTF_8);
        byte[] second = (prefix + ":second").getBytes(StandardCharsets.UTF_8);
        String firstId = "INVALID_SHA256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(first)).substring(0, 48);
        String secondId = "INVALID_SHA256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(second)).substring(0, 48);
        assertThat(firstId).isNotEqualTo(secondId);

        rabbitTemplate.convertAndSend(EventNotificationQueueConfig.EXCHANGE,
                EventNotificationQueueConfig.ROUTING_KEY, first);
        rabbitTemplate.convertAndSend(EventNotificationQueueConfig.EXCHANGE,
                EventNotificationQueueConfig.ROUTING_KEY, second);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification_failure"
                    + " WHERE event_id IN (?,?) AND failure_kind='UNPROCESSABLE'",
                    Integer.class, firstId, secondId)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT original_body FROM et_notification_failure WHERE event_id=?",
                    byte[].class, firstId)).isEqualTo(first);
            assertThat(db.queryForObject("SELECT original_body FROM et_notification_failure WHERE event_id=?",
                    byte[].class, secondId)).isEqualTo(second);
        });
    }

    @Test
    void technicalConsumerFailureStopsAfterBoundedRetryAndCanBeRedriven() {
        var order = orders.create(80, "notification-technical-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(order.id(), 80);
        String eventId = "ORDER_CLOSED:" + order.id();
        // A conflicting stored row makes the insert fail with a technical
        // uniqueness error; the mismatch must not be mistaken for idempotency.
        db.update("""
                INSERT INTO et_notification(id,event_id,user_id,order_id,notification_type,content)
                VALUES (?,?,?,?,?,?)
                """, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), eventId,
                999999, order.id(), "ORDER_CLOSED", "conflicting fixture");
        try {
            publisher.publish();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(db.queryForList("SELECT failure_kind FROM et_notification_failure WHERE event_id=?",
                        String.class, eventId)).containsExactly("TECHNICAL");
                assertThat(admin.getQueueInfo(EventNotificationQueueConfig.DLQ).getMessageCount())
                        .isGreaterThanOrEqualTo(1);
            });
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=? AND user_id=?",
                    Integer.class, eventId, 80)).isZero();
        } finally {
            db.update("DELETE FROM et_notification WHERE event_id=? AND user_id=?", eventId, 999999);
        }
        var result = redrive.redrive(eventId, 9001, "Storage failure removed");
        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, eventId)).isEqualTo(1));
    }

    @Test
    void publishedEventWithMissingEffectCanBeAuditedAndSafelyRedriven() {
        var order = orders.create(81, "notification-gap-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(order.id(), 81);
        String eventId = "ORDER_CLOSED:" + order.id();
        // Models an event accepted by the old queue before an upgrade but
        // absent from the notification table during operator reconciliation.
        db.update("UPDATE et_outbox_event SET publish_status='PUBLISHED' WHERE event_id=?", eventId);
        var result = redrive.redrive(eventId, 9001, "Missing effect after queue migration");
        assertThat(result.eventId()).isEqualTo(eventId);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, eventId)).isEqualTo(1));
        assertThat(db.queryForObject("SELECT failure_kind FROM et_notification_failure WHERE event_id=?",
                String.class, eventId)).isEqualTo("MANUAL_GAP");
        assertThatThrownBy(() -> redrive.redrive(eventId, 9001, "Duplicate operation"))
                .hasMessageContaining("already exists");
    }

    @Test
    void exhaustedPublisherCanBeReactivatedWithAuditAndSameEventId() {
        var order = orders.create(82, "notification-publisher-redrive-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(order.id(), 82);
        String eventId = "ORDER_CLOSED:" + order.id();
        db.update("""
                UPDATE et_outbox_event SET publish_status='MANUAL_REQUIRED',attempts=8,
                    last_error='broker unavailable' WHERE event_id=?
                """, eventId);
        var result = redrive.redrive(eventId, 9001, "Broker route repaired");
        assertThat(result.outcome()).isEqualTo("PENDING");
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                Integer.class, eventId)).isZero();
        assertThat(db.queryForMap("SELECT event_id,actor_id,action,outcome FROM et_notification_redrive WHERE id=?",
                result.operationId())).containsEntry("event_id", eventId)
                .containsEntry("actor_id", 9001L).containsEntry("action", "REACTIVATE_PUBLISH")
                .containsEntry("outcome", "PENDING");
        publisher.publish();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(status(eventId)).isEqualTo("PUBLISHED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?",
                    Integer.class, eventId)).isEqualTo(1);
        });
    }

    @Test
    void expiredRedriveLeaseKeepsPriorOperationAsUncertain() {
        var order = orders.create(83, "notification-redrive-lease-" + UUID.randomUUID(), tier(), 1);
        orders.cancel(order.id(), 83);
        String eventId = "ORDER_CLOSED:" + order.id();
        db.update("UPDATE et_outbox_event SET publish_status='PUBLISHED' WHERE event_id=?", eventId);
        db.update("""
                INSERT INTO et_notification_failure
                (event_id,original_body,failure_kind,failure_reason,status,lease_until)
                VALUES (?,?,'TECHNICAL','injected lost operator','REDRIVING',DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND))
                """, eventId, eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long oldOperation = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        db.update("""
                INSERT INTO et_notification_redrive(id,event_id,actor_id,reason,action,outcome)
                VALUES (?,?,9001,'Previous attempt','REDRIVE_CONSUMER','STARTED')
                """, oldOperation, eventId);
        var result = redrive.redrive(eventId, 9002, "Recover expired lease");
        assertThat(result.outcome()).isEqualTo("CONFIRMED");
        assertThat(db.queryForMap("SELECT outcome,detail FROM et_notification_redrive WHERE id=?",
                oldOperation)).containsEntry("outcome", "UNCERTAIN")
                .containsEntry("detail", "Redrive lease expired");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM et_notification WHERE event_id=?", Integer.class, eventId)).isEqualTo(1));
    }

    @Test
    void publisherCrashBeforeAndAfterConfirmRecoversTheSameEvents() {
        for (boolean afterConfirm : List.of(false, true)) {
            long userId = afterConfirm ? 79 : 78;
            var order = orders.create(userId, "notification-publisher-crash-" + UUID.randomUUID(), tier(), 1);
            orders.cancel(order.id(), userId);
            String eventId = "ORDER_CLOSED:" + order.id();
            db.update("UPDATE et_outbox_event SET next_attempt_at=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 60 SECOND)"
                    + " WHERE event_id=?", eventId);
            EventNotificationPublisher crashing = new EventNotificationPublisher(db, rabbitTemplate, admin,
                    eventNotificationExchange, eventNotificationQueue, eventNotificationBinding,
                    1, 8, 30, 5) {
                @Override
                protected void beforeConfirm(String id) {
                    if (!afterConfirm) throw new AssertionError("injected process loss before confirm");
                }

                @Override
                protected void afterConfirm(String id) {
                    if (afterConfirm) throw new AssertionError("injected process loss after confirm");
                }
            };
            assertThatThrownBy(crashing::publish).isInstanceOf(AssertionError.class);
            assertThat(status(eventId)).isEqualTo("PROCESSING");
            db.update("UPDATE et_outbox_event SET lease_until=DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND)"
                    + " WHERE event_id=?", eventId);
            publisher.publish();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(status(eventId)).isEqualTo("PUBLISHED");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM et_notification WHERE event_id=?",
                        Integer.class, eventId)).isEqualTo(1);
            });
            assertThat(db.queryForObject("SELECT attempts FROM et_outbox_event WHERE event_id=?",
                    Integer.class, eventId)).isEqualTo(2);
        }
    }

    private long tier() {
        Instant now = Instant.now();
        long event = catalog.createEvent(1, "Notification acceptance", null, "Test venue");
        long session = catalog.addSession(event, "Main", now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        long tier = catalog.addTicketTier(session, "Standard", 4750, "CNY", 1);
        catalog.publish(event);
        return tier;
    }

    private String status(String eventId) {
        return db.queryForObject("SELECT publish_status FROM et_outbox_event WHERE event_id=?",
                String.class, eventId);
    }
}
