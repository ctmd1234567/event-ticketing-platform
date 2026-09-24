package com.eventplatform;

import com.eventplatform.catalog.EventCatalogService;
import com.eventplatform.order.EventOrderService;
import com.eventplatform.notification.EventNotificationOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class EventOrderServiceTest {
    private JdbcTemplate db;
    private EventCatalogService catalog;
    private EventOrderService orders;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:event_orders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("DROP ALL OBJECTS");
        db.execute("""
            CREATE TABLE et_event(
              id BIGINT PRIMARY KEY,title VARCHAR(160),description VARCHAR(2000),venue VARCHAR(255),
              status VARCHAR(16),created_by BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE et_event_session(
              id BIGINT PRIMARY KEY,event_id BIGINT,name VARCHAR(160),starts_at TIMESTAMP,ends_at TIMESTAMP,
              sales_start_at TIMESTAMP,sales_end_at TIMESTAMP,status VARCHAR(16))
            """);
        db.execute("""
            CREATE TABLE et_ticket_tier(
              id BIGINT PRIMARY KEY,session_id BIGINT,name VARCHAR(120),unit_price BIGINT,currency CHAR(3),
              capacity INT,available INT,reserved INT,allocated INT,purchase_limit_per_user INT,status VARCHAR(16))
            """);
        db.execute("""
            CREATE TABLE et_order(
              id BIGINT PRIMARY KEY,order_number VARCHAR(32) UNIQUE,user_id BIGINT,event_id BIGINT,
              session_id BIGINT,ticket_tier_id BIGINT,quantity INT,unit_price BIGINT,total_amount BIGINT,
              currency CHAR(3),status VARCHAR(24),idempotency_key VARCHAR(128),request_hash VARCHAR(160),
              payment_deadline TIMESTAMP,close_reason VARCHAR(32),expiry_attempts INT DEFAULT 0,
              expiry_next_attempt_at TIMESTAMP,expiry_last_error VARCHAR(255),
              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
              UNIQUE(user_id,idempotency_key),UNIQUE(user_id,ticket_tier_id))
            """);
        db.execute("""
            CREATE TABLE et_inventory_reservation(
              id BIGINT PRIMARY KEY,order_id BIGINT UNIQUE,ticket_tier_id BIGINT,quantity INT,
              status VARCHAR(16),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE et_outbox_event(
              event_id VARCHAR(64) PRIMARY KEY,event_type VARCHAR(32),aggregate_id BIGINT,user_id BIGINT,
              payload VARCHAR(1000),publish_status VARCHAR(24) DEFAULT 'PENDING',
              attempts INT DEFAULT 0,next_attempt_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
            """);
        catalog = new EventCatalogService(db);
        orders = new EventOrderService(db, new DataSourceTransactionManager(source),
                new EventNotificationOutbox(db, new ObjectMapper()), 900, 30,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void createsOwnedOrderWithServerPriceAndAtomicReservation() {
        Fixture fixture = sellableFixture();

        var order = orders.create(7, "order-key-0000001", fixture.firstTierId(), 1);

        assertThat(order.unitPrice()).isEqualTo(4750);
        assertThat(order.totalAmount()).isEqualTo(4750);
        assertThat(order.currency()).isEqualTo("CNY");
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(orders.create(7, "order-key-0000001", fixture.firstTierId(), 1).id())
                .isEqualTo(order.id());
        assertThat(orders.order(order.id(), 7).id()).isEqualTo(order.id());
        assertThat(orders.orders(7)).extracting(EventOrderService.OrderView::id).containsExactly(order.id());
        assertThat(db.queryForMap("SELECT available,reserved,allocated FROM et_ticket_tier WHERE id=?",
                fixture.firstTierId())).containsEntry("available", 1).containsEntry("reserved", 1)
                .containsEntry("allocated", 0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_inventory_reservation WHERE order_id=? AND status='RESERVED'",
                Integer.class, order.id())).isOne();
        assertThatThrownBy(() -> orders.order(order.id(), 8))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejectsChangedReplaySecondPurchaseAndInvalidQuantity() {
        Fixture fixture = sellableFixture();
        orders.create(7, "order-key-0000001", fixture.firstTierId(), 1);

        assertThatThrownBy(() -> orders.create(7, "order-key-0000001", fixture.secondTierId(), 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> orders.create(7, "order-key-0000002", fixture.firstTierId(), 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> orders.create(8, "order-key-0000003", fixture.firstTierId(), 2))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(db.queryForObject("SELECT available FROM et_ticket_tier WHERE id=?",
                Integer.class, fixture.firstTierId())).isEqualTo(1);
    }

    @Test
    void rejectsMissingFutureAndStoppedTicketTiers() {
        assertThatThrownBy(() -> orders.create(7, "order-key-0000001", Long.MAX_VALUE, 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        Instant now = Instant.now();
        long eventId = catalog.createEvent(1, "Concert", null, "Hall A");
        long sessionId = catalog.addSession(eventId, "Evening",
                now.plusSeconds(7200), now.plusSeconds(10800),
                now.plusSeconds(60), now.plusSeconds(3600));
        long tierId = catalog.addTicketTier(sessionId, "Standard", 4750, "CNY", 2);
        catalog.publish(eventId);
        assertThatThrownBy(() -> orders.create(7, "order-key-0000002", tierId, 1))
                .isInstanceOf(ResponseStatusException.class);

        db.update("UPDATE et_event_session SET sales_start_at=?,sales_end_at=? WHERE id=?",
                java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(3600)), sessionId);
        catalog.takeOffSale(eventId);
        assertThatThrownBy(() -> orders.create(7, "order-key-0000003", tierId, 1))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT available FROM et_ticket_tier WHERE id=?", Integer.class, tierId))
                .isEqualTo(2);
    }

    @Test
    void rollsBackInventoryAndOrderWhenReservationInsertFails() {
        Fixture fixture = sellableFixture();
        db.execute("DROP TABLE et_inventory_reservation");

        assertThatThrownBy(() -> orders.create(7, "order-key-rollback-01", fixture.firstTierId(), 1))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_order", Integer.class)).isZero();
        assertThat(db.queryForMap("SELECT capacity,available,reserved,allocated "
                + "FROM et_ticket_tier WHERE id=?", fixture.firstTierId()))
                .containsEntry("capacity", 2)
                .containsEntry("available", 2)
                .containsEntry("reserved", 0)
                .containsEntry("allocated", 0);
    }

    @Test
    void cancelIsOwnedAtomicAndIdempotent() {
        Fixture fixture = sellableFixture();
        var order = orders.create(7, "order-key-cancel-001", fixture.firstTierId(), 1);

        assertThatThrownBy(() -> orders.cancel(order.id(), 8))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        var canceled = orders.cancel(order.id(), 7);
        var repeated = orders.cancel(order.id(), 7);

        assertThat(canceled.status()).isEqualTo("CLOSED");
        assertThat(canceled.closeReason()).isEqualTo("USER_CANCELED");
        assertThat(repeated).isEqualTo(canceled);
        assertThat(db.queryForMap("SELECT capacity,available,reserved,allocated "
                + "FROM et_ticket_tier WHERE id=?", fixture.firstTierId()))
                .containsEntry("capacity", 2)
                .containsEntry("available", 2)
                .containsEntry("reserved", 0)
                .containsEntry("allocated", 0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_inventory_reservation "
                + "WHERE order_id=? AND status='RELEASED'", Integer.class, order.id())).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_inventory_reservation "
                + "WHERE order_id=? AND status='RESERVED'", Integer.class, order.id())).isZero();
        assertThatThrownBy(() -> orders.create(7, "order-key-cancel-002",
                fixture.firstTierId(), 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void persistentDeadlineScanClosesOnlyExpiredOrdersOnce() {
        Fixture fixture = sellableFixture();
        var order = orders.create(7, "order-key-expiry-001", fixture.firstTierId(), 1);

        assertThat(orders.closeExpiredBatch(10)).isZero();
        db.update("UPDATE et_order SET payment_deadline=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), order.id());

        assertThat(orders.closeExpiredBatch(10)).isOne();
        assertThat(orders.closeExpiredBatch(10)).isZero();
        assertThat(orders.order(order.id(), 7).status()).isEqualTo("CLOSED");
        assertThat(orders.order(order.id(), 7).closeReason()).isEqualTo("PAYMENT_EXPIRED");
        assertThat(db.queryForMap("SELECT capacity,available,reserved,allocated "
                + "FROM et_ticket_tier WHERE id=?", fixture.firstTierId()))
                .containsEntry("capacity", 2)
                .containsEntry("available", 2)
                .containsEntry("reserved", 0)
                .containsEntry("allocated", 0);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM et_inventory_reservation "
                + "WHERE order_id=? AND status='RELEASED'", Integer.class, order.id())).isOne();
    }

    @Test
    void failedExpiryIsDeferredWithoutStarvingLaterOrders() {
        Fixture poisonFixture = sellableFixture();
        var poison = orders.create(7, "order-key-poison-0001", poisonFixture.firstTierId(), 1);
        Fixture healthyFixture = sellableFixture();
        var healthy = orders.create(8, "order-key-healthy-0001", healthyFixture.firstTierId(), 1);
        db.update("DELETE FROM et_inventory_reservation WHERE order_id=?", poison.id());
        db.update("UPDATE et_order SET payment_deadline=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(2)), poison.id());
        db.update("UPDATE et_order SET payment_deadline=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), healthy.id());

        assertThat(orders.closeExpiredBatch(1)).isZero();
        assertThat(db.queryForMap("SELECT expiry_attempts,expiry_next_attempt_at,expiry_last_error "
                + "FROM et_order WHERE id=?", poison.id()))
                .containsEntry("expiry_attempts", 1);
        assertThat(db.queryForObject("SELECT expiry_next_attempt_at IS NOT NULL FROM et_order WHERE id=?",
                Boolean.class, poison.id())).isTrue();
        assertThat(orders.closeExpiredBatch(1)).isOne();
        assertThat(orders.order(healthy.id(), 8).status()).isEqualTo("CLOSED");
        assertThat(orders.order(poison.id(), 7).status()).isEqualTo("PENDING_PAYMENT");
        db.update("UPDATE et_order SET expiry_next_attempt_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), poison.id());
        assertThat(orders.closeExpiredBatch(1)).isZero();
        assertThat(db.queryForObject("SELECT expiry_attempts FROM et_order WHERE id=?",
                Integer.class, poison.id())).isEqualTo(2);
    }

    @Test
    void paidOrderWinsAgainstCancellationWithoutReleasingInventory() {
        Fixture fixture = sellableFixture();
        var order = orders.create(7, "order-key-paid-0001", fixture.firstTierId(), 1);
        db.update("UPDATE et_order SET status='PAID' WHERE id=?", order.id());
        db.update("UPDATE et_inventory_reservation SET status='CONFIRMED' WHERE order_id=?", order.id());
        db.update("UPDATE et_ticket_tier SET reserved=reserved-1,allocated=allocated+1 WHERE id=?",
                fixture.firstTierId());

        assertThatThrownBy(() -> orders.cancel(order.id(), 7))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(db.queryForMap("SELECT capacity,available,reserved,allocated "
                + "FROM et_ticket_tier WHERE id=?", fixture.firstTierId()))
                .containsEntry("capacity", 2)
                .containsEntry("available", 1)
                .containsEntry("reserved", 0)
                .containsEntry("allocated", 1);
        assertThat(db.queryForObject("SELECT status FROM et_inventory_reservation WHERE order_id=?",
                String.class, order.id())).isEqualTo("CONFIRMED");
    }

    private Fixture sellableFixture() {
        Instant now = Instant.now();
        long eventId = catalog.createEvent(1, "Concert", null, "Hall A");
        long sessionId = catalog.addSession(eventId, "Evening",
                now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        long first = catalog.addTicketTier(sessionId, "Standard", 4750, "CNY", 2);
        long second = catalog.addTicketTier(sessionId, "Premium", 9750, "CNY", 2);
        catalog.publish(eventId);
        return new Fixture(first, second);
    }

    private record Fixture(long firstTierId, long secondTierId) {}
}
