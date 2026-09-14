package com.eventplatform.catalog;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class EventCatalogService {
    private final JdbcTemplate db;

    public EventCatalogService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public long createEvent(long actorId, String title, String description, String venue) {
        requireText(title, "Event title is required");
        requireText(venue, "Event venue is required");
        long id = IdWorker.getId();
        db.update("""
            INSERT INTO et_event(id,title,description,venue,status,created_by)
            VALUES (?,?,?,?,'DRAFT',?)
            """, id, title.trim(), blankToNull(description), venue.trim(), actorId);
        return id;
    }

    @Transactional
    public long addSession(long eventId, String name, Instant startsAt, Instant endsAt,
            Instant salesStartAt, Instant salesEndAt) {
        requireText(name, "Session name is required");
        if (startsAt == null || endsAt == null || salesStartAt == null || salesEndAt == null
                || !startsAt.isBefore(endsAt)
                || !salesStartAt.isBefore(salesEndAt)
                || salesEndAt.isAfter(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session time range");
        }
        requireStatus("SELECT status FROM et_event WHERE id=? FOR UPDATE", eventId, "DRAFT", "Event");
        long id = IdWorker.getId();
        db.update("""
            INSERT INTO et_event_session(
                id,event_id,name,starts_at,ends_at,sales_start_at,sales_end_at,status)
            VALUES (?,?,?,?,?,?,?,'DRAFT')
            """, id, eventId, name.trim(), Timestamp.from(startsAt), Timestamp.from(endsAt),
                Timestamp.from(salesStartAt), Timestamp.from(salesEndAt));
        return id;
    }

    @Transactional
    public long addTicketTier(long sessionId, String name, long unitPrice, String currency, int capacity) {
        requireText(name, "Ticket tier name is required");
        if (unitPrice < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket price must not be negative");
        }
        if (capacity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket capacity must be positive");
        }
        if (!"CNY".equals(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "V1 currency must be CNY");
        }
        String eventStatus;
        try {
            eventStatus = db.queryForObject("""
                SELECT e.status
                FROM et_event_session s JOIN et_event e ON e.id=s.event_id
                WHERE s.id=? AND s.status='DRAFT'
                FOR UPDATE
                """, String.class, sessionId);
        } catch (EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft session not found");
        }
        if (!"DRAFT".equals(eventStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event is no longer editable");
        }
        long id = IdWorker.getId();
        db.update("""
            INSERT INTO et_ticket_tier(
                id,session_id,name,unit_price,currency,capacity,available,reserved,allocated,
                purchase_limit_per_user,status)
            VALUES (?,?,?,?,?,?,?,0,0,1,'DRAFT')
            """, id, sessionId, name.trim(), unitPrice, currency, capacity, capacity);
        return id;
    }

    @Transactional
    public void publish(long eventId) {
        requireStatus("SELECT status FROM et_event WHERE id=? FOR UPDATE", eventId, "DRAFT", "Event");
        Integer sellableSessions = db.queryForObject("""
            SELECT COUNT(DISTINCT s.id)
            FROM et_event_session s JOIN et_ticket_tier t ON t.session_id=s.id
            WHERE s.event_id=? AND s.status='DRAFT' AND t.status='DRAFT'
            """, Integer.class, eventId);
        if (sellableSessions == null || sellableSessions == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Event requires at least one session and ticket tier");
        }
        db.update("""
            UPDATE et_ticket_tier SET status='ON_SALE'
            WHERE status='DRAFT' AND session_id IN (
                SELECT id FROM et_event_session WHERE event_id=?
            )
            """, eventId);
        db.update("UPDATE et_event_session SET status='ON_SALE' WHERE event_id=? AND status='DRAFT'", eventId);
        db.update("UPDATE et_event SET status='PUBLISHED' WHERE id=? AND status='DRAFT'", eventId);
    }

    @Transactional
    public void takeOffSale(long eventId) {
        int changed = db.update("UPDATE et_event SET status='OFF_SALE' WHERE id=? AND status='PUBLISHED'", eventId);
        if (changed == 0) {
            requireStatus("SELECT status FROM et_event WHERE id=?", eventId, "PUBLISHED", "Event");
        }
        db.update("""
            UPDATE et_ticket_tier SET status='OFF_SALE'
            WHERE status='ON_SALE' AND session_id IN (
                SELECT id FROM et_event_session WHERE event_id=?
            )
            """, eventId);
        db.update("UPDATE et_event_session SET status='OFF_SALE' WHERE event_id=? AND status='ON_SALE'", eventId);
    }

    @Transactional(readOnly = true)
    public List<EventView> listEvents() {
        return db.query("""
            SELECT id,title,description,venue,status
            FROM et_event WHERE status='PUBLISHED'
            ORDER BY created_at DESC,id DESC
            """, (rs, row) -> new EventView(rs.getLong("id"), rs.getString("title"),
                rs.getString("description"), rs.getString("venue"), rs.getString("status")));
    }

    @Transactional(readOnly = true)
    public EventDetails event(long eventId) {
        EventView event;
        try {
            event = db.queryForObject("""
                SELECT id,title,description,venue,status
                FROM et_event WHERE id=? AND status<>'DRAFT'
                """, (rs, row) -> new EventView(rs.getLong("id"), rs.getString("title"),
                    rs.getString("description"), rs.getString("venue"), rs.getString("status")), eventId);
        } catch (EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        List<SessionView> sessions = db.query("""
            SELECT id,event_id,name,starts_at,ends_at,sales_start_at,sales_end_at,status
            FROM et_event_session WHERE event_id=? ORDER BY starts_at,id
            """, (rs, row) -> new SessionView(rs.getLong("id"), rs.getLong("event_id"),
                rs.getString("name"), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(), rs.getTimestamp("sales_start_at").toInstant(),
                rs.getTimestamp("sales_end_at").toInstant(), rs.getString("status")), eventId);
        return new EventDetails(event, sessions);
    }

    @Transactional(readOnly = true)
    public List<TierView> ticketTiers(long sessionId) {
        return db.query("""
            SELECT t.id,t.session_id,t.name,t.unit_price,t.currency,t.capacity,
                   t.available,t.reserved,t.allocated,t.status
            FROM et_ticket_tier t
            JOIN et_event_session s ON s.id=t.session_id
            JOIN et_event e ON e.id=s.event_id
            WHERE t.session_id=? AND e.status<>'DRAFT'
            ORDER BY t.unit_price,t.id
            """, (rs, row) -> tier(rs), sessionId);
    }

    @Transactional(readOnly = true)
    public TierView requireSellable(long tierId, int quantity) {
        if (quantity != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "V1 order quantity must be 1");
        }
        Integer present = db.queryForObject("SELECT COUNT(*) FROM et_ticket_tier WHERE id=?", Integer.class, tierId);
        if (present == null || present == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket tier not found");
        }
        try {
            return db.queryForObject("""
                SELECT t.id,t.session_id,t.name,t.unit_price,t.currency,t.capacity,
                       t.available,t.reserved,t.allocated,t.status
                FROM et_ticket_tier t
                JOIN et_event_session s ON s.id=t.session_id
                JOIN et_event e ON e.id=s.event_id
                WHERE t.id=? AND e.status='PUBLISHED' AND s.status='ON_SALE'
                  AND t.status='ON_SALE' AND t.available>0
                  AND CURRENT_TIMESTAMP>=s.sales_start_at AND CURRENT_TIMESTAMP<s.sales_end_at
                """, (rs, row) -> tier(rs), tierId);
        } catch (EmptyResultDataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ticket tier is not sellable");
        }
    }

    private TierView tier(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TierView(rs.getLong("id"), rs.getLong("session_id"), rs.getString("name"),
                rs.getLong("unit_price"), rs.getString("currency"), rs.getInt("capacity"),
                rs.getInt("available"), rs.getInt("reserved"), rs.getInt("allocated"),
                rs.getString("status"));
    }

    private String requireStatus(String sql, long id, String expected, String resource) {
        String status;
        try {
            status = db.queryForObject(sql, String.class, id);
        } catch (EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, resource + " not found");
        }
        if (!expected.equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    resource + " must be " + expected + " but was " + status);
        }
        return status;
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record EventView(long id, String title, String description, String venue, String status) {}
    public record SessionView(long id, long eventId, String name, Instant startsAt, Instant endsAt,
            Instant salesStartAt, Instant salesEndAt, String status) {}
    public record TierView(long id, long sessionId, String name, long unitPrice, String currency,
            int capacity, int available, int reserved, int allocated, String status) {}
    public record EventDetails(EventView event, List<SessionView> sessions) {}
}
