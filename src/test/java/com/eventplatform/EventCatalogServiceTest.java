package com.eventplatform;

import com.eventplatform.catalog.EventCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class EventCatalogServiceTest {
    private JdbcTemplate db;
    private EventCatalogService catalog;

    @BeforeEach
    void setUp() {
        db = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:catalog;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
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
        catalog = new EventCatalogService(db);
    }

    @Test
    void createsPublishesAndQueriesSellableCatalog() {
        Instant now = Instant.now();
        long eventId = catalog.createEvent(1, "Concert", "Demo", "Hall A");
        long sessionId = catalog.addSession(eventId, "Evening",
                now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        long tierId = catalog.addTicketTier(sessionId, "Standard", 4750, "CNY", 100);

        catalog.publish(eventId);

        assertThat(catalog.listEvents()).extracting(EventCatalogService.EventView::id)
                .containsExactly(eventId);
        assertThat(catalog.event(eventId).sessions()).extracting(EventCatalogService.SessionView::id)
                .containsExactly(sessionId);
        assertThat(catalog.ticketTiers(sessionId)).singleElement().satisfies(tier -> {
            assertThat(tier.id()).isEqualTo(tierId);
            assertThat(tier.available() + tier.reserved() + tier.allocated()).isEqualTo(tier.capacity());
        });
        assertThat(catalog.requireSellable(tierId, 1).unitPrice()).isEqualTo(4750);
    }

    @Test
    void rejectsInvalidRulesAndStoppedSales() {
        Instant now = Instant.now();
        long eventId = catalog.createEvent(1, "Concert", null, "Hall A");

        assertThatThrownBy(() -> catalog.addSession(eventId, "Broken",
                now.plusSeconds(10), now, now.minusSeconds(20), now.minusSeconds(10)))
                .isInstanceOf(ResponseStatusException.class);

        long sessionId = catalog.addSession(eventId, "Evening",
                now.plusSeconds(7200), now.plusSeconds(10800),
                now.minusSeconds(60), now.plusSeconds(3600));
        assertThatThrownBy(() -> catalog.addTicketTier(sessionId, "Bad", -1, "CNY", 1))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> catalog.addTicketTier(sessionId, "Bad", 1, "USD", 1))
                .isInstanceOf(ResponseStatusException.class);

        long tierId = catalog.addTicketTier(sessionId, "Standard", 4750, "CNY", 100);
        assertThatThrownBy(() -> catalog.requireSellable(Long.MAX_VALUE, 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> catalog.requireSellable(tierId, 1))
                .isInstanceOf(ResponseStatusException.class);
        catalog.publish(eventId);
        assertThat(catalog.requireSellable(tierId, 1))
                .returns(4750L, EventCatalogService.TierView::unitPrice)
                .returns("CNY", EventCatalogService.TierView::currency);
        assertThatThrownBy(() -> catalog.requireSellable(tierId, 2))
                .isInstanceOf(ResponseStatusException.class);
        catalog.takeOffSale(eventId);
        assertThatThrownBy(() -> catalog.requireSellable(tierId, 1))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(catalog.listEvents()).isEmpty();
    }

    @Test
    void rejectsFutureAndExpiredSalesWindows() {
        Instant now = Instant.now();
        long eventId = catalog.createEvent(1, "Concert", null, "Hall A");
        long sessionId = catalog.addSession(eventId, "Evening",
                now.plusSeconds(7200), now.plusSeconds(10800),
                now.plusSeconds(60), now.plusSeconds(3600));
        long tierId = catalog.addTicketTier(sessionId, "Standard", 4750, "CNY", 100);
        catalog.publish(eventId);

        assertThatThrownBy(() -> catalog.requireSellable(tierId, 1))
                .isInstanceOf(ResponseStatusException.class);

        db.update("UPDATE et_event_session SET sales_start_at=?,sales_end_at=? WHERE id=?",
                java.sql.Timestamp.from(now.minusSeconds(3600)),
                java.sql.Timestamp.from(now.minusSeconds(1)), sessionId);
        assertThatThrownBy(() -> catalog.requireSellable(tierId, 1))
                .isInstanceOf(ResponseStatusException.class);
    }
}
