package com.eventplatform.order;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class EventOrderService {
    private static final Logger log = LoggerFactory.getLogger(EventOrderService.class);
    private static final String USER_CANCELED = "USER_CANCELED";
    private static final String PAYMENT_EXPIRED = "PAYMENT_EXPIRED";

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final Duration paymentWindow;
    private final Duration expiryFailureRetry;

    public EventOrderService(JdbcTemplate db, PlatformTransactionManager transactionManager,
            @Value("${app.event-orders.payment-window-seconds:900}") long paymentWindowSeconds,
            @Value("${app.event-orders.expiry-failure-retry-seconds:30}") long expiryFailureRetrySeconds) {
        this.db = db;
        this.transactions = new TransactionTemplate(transactionManager);
        this.paymentWindow = Duration.ofSeconds(Math.max(1, paymentWindowSeconds));
        this.expiryFailureRetry = Duration.ofSeconds(Math.max(1, expiryFailureRetrySeconds));
    }

    public OrderView create(long userId, String idempotencyKey, long ticketTierId, int quantity) {
        validateIdempotencyKey(idempotencyKey);
        if (ticketTierId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket tier id must be positive");
        }
        if (quantity != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "V1 order quantity must be 1");
        }
        String requestHash = ticketTierId + ":" + quantity;
        OrderView existing = findByKey(userId, idempotencyKey);
        if (existing != null) {
            return replay(existing, requestHash);
        }

        try {
            OrderView created = transactions.execute(status -> createNew(
                    userId, idempotencyKey, requestHash, ticketTierId, quantity));
            if (created == null) {
                throw new IllegalStateException("Order transaction returned no result");
            }
            return created;
        } catch (DuplicateKeyException duplicate) {
            existing = findByKey(userId, idempotencyKey);
            if (existing != null) {
                return replay(existing, requestHash);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "V1 purchase limit already reached for this ticket tier", duplicate);
        }
    }

    private OrderView createNew(long userId, String idempotencyKey, String requestHash,
            long ticketTierId, int quantity) {
        SellableTier tier = lockTier(ticketTierId);
        Instant now = databaseNow();
        if (!"PUBLISHED".equals(tier.eventStatus())
                || !"ON_SALE".equals(tier.sessionStatus())
                || !"ON_SALE".equals(tier.tierStatus())
                || now.isBefore(tier.salesStartAt())
                || !now.isBefore(tier.salesEndAt())
                || tier.available() < quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ticket tier is not sellable");
        }
        if (!"CNY".equals(tier.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "V1 currency must be CNY");
        }

        int changed = db.update("""
            UPDATE et_ticket_tier
            SET available=available-?, reserved=reserved+?
            WHERE id=? AND status='ON_SALE' AND available>=?
            """, quantity, quantity, ticketTierId, quantity);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ticket tier is sold out");
        }

        long orderId = IdWorker.getId();
        String orderNumber = "EO" + orderId;
        long totalAmount = Math.multiplyExact(tier.unitPrice(), quantity);
        Instant paymentDeadline = min(now.plus(paymentWindow), tier.salesEndAt());
        db.update("""
            INSERT INTO et_order(
                id,order_number,user_id,event_id,session_id,ticket_tier_id,quantity,
                unit_price,total_amount,currency,status,idempotency_key,request_hash,payment_deadline)
            VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING_PAYMENT',?,?,?)
            """, orderId, orderNumber, userId, tier.eventId(), tier.sessionId(), ticketTierId,
                quantity, tier.unitPrice(), totalAmount, tier.currency(), idempotencyKey,
                requestHash, Timestamp.from(paymentDeadline));
        db.update("""
            INSERT INTO et_inventory_reservation(id,order_id,ticket_tier_id,quantity,status)
            VALUES (?,?,?,?,'RESERVED')
            """, IdWorker.getId(), orderId, ticketTierId, quantity);
        return order(orderId, userId);
    }

    @Transactional(readOnly = true)
    public OrderView order(long orderId, long userId) {
        try {
            return db.queryForObject("""
                SELECT id,order_number,user_id,event_id,session_id,ticket_tier_id,quantity,
                       unit_price,total_amount,currency,status,idempotency_key,request_hash,
                       payment_deadline,close_reason,created_at
                FROM et_order WHERE id=? AND user_id=?
                """, (rs, row) -> mapOrder(rs), orderId, userId);
        } catch (EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }

    @Transactional(readOnly = true)
    public List<OrderView> orders(long userId) {
        return db.query("""
            SELECT id,order_number,user_id,event_id,session_id,ticket_tier_id,quantity,
                   unit_price,total_amount,currency,status,idempotency_key,request_hash,
                   payment_deadline,close_reason,created_at
            FROM et_order WHERE user_id=? ORDER BY created_at DESC,id DESC
            """, (rs, row) -> mapOrder(rs), userId);
    }

    public OrderView cancel(long orderId, long userId) {
        Boolean closed = transactions.execute(status -> close(orderId, userId, USER_CANCELED, false));
        if (closed == null) {
            throw new IllegalStateException("Cancel transaction returned no result");
        }
        return order(orderId, userId);
    }

    public int closeExpiredBatch(int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 1000));
        List<Long> candidates = db.queryForList("""
            SELECT id
            FROM et_order
            WHERE status='PENDING_PAYMENT' AND payment_deadline<=CURRENT_TIMESTAMP
              AND (expiry_next_attempt_at IS NULL OR expiry_next_attempt_at<=CURRENT_TIMESTAMP)
            ORDER BY payment_deadline,id
            LIMIT
            """ + batchSize, Long.class);
        int closed = 0;
        for (Long orderId : candidates) {
            try {
                Boolean changed = transactions.execute(
                        status -> close(orderId, null, PAYMENT_EXPIRED, true));
                if (Boolean.TRUE.equals(changed)) {
                    closed++;
                }
            } catch (RuntimeException failure) {
                log.error("Failed to close expired event order {}", orderId, failure);
                deferFailedExpiry(orderId, failure);
            }
        }
        return closed;
    }

    private void deferFailedExpiry(long orderId, RuntimeException failure) {
        String detail = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            detail += ": " + failure.getMessage();
        }
        if (detail.length() > 255) {
            detail = detail.substring(0, 255);
        }
        try {
            db.update("""
                UPDATE et_order
                SET expiry_attempts=expiry_attempts+1,
                    expiry_next_attempt_at=?,expiry_last_error=?
                WHERE id=? AND status='PENDING_PAYMENT'
                """, Timestamp.from(databaseNow().plus(expiryFailureRetry)), detail, orderId);
        } catch (RuntimeException deferFailure) {
            failure.addSuppressed(deferFailure);
            log.error("Failed to defer expiry retry for event order {}", orderId, deferFailure);
        }
    }

    private boolean close(long orderId, Long expectedUserId, String closeReason, boolean requireExpired) {
        LockedOrder order = lockOrder(orderId);
        if (expectedUserId != null && order.userId() != expectedUserId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        if ("CLOSED".equals(order.status())) {
            return false;
        }
        if (!"PENDING_PAYMENT".equals(order.status())) {
            // A selected expiry candidate may have been paid before we acquired its lock.
            if (requireExpired && "PAID".equals(order.status())) {
                return false;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The order cannot be canceled in its current state");
        }
        if (requireExpired && databaseNow().isBefore(order.paymentDeadline())) {
            return false;
        }

        LockedReservation reservation = lockReservation(orderId);
        if (!"RESERVED".equals(reservation.status())
                || reservation.ticketTierId() != order.ticketTierId()
                || reservation.quantity() != order.quantity()) {
            throw new IllegalStateException("Pending order does not own its reserved inventory");
        }
        lockTierInventory(order.ticketTierId());

        int orderChanged = db.update("""
            UPDATE et_order SET status='CLOSED',close_reason=?
            WHERE id=? AND status='PENDING_PAYMENT'
            """, closeReason, orderId);
        int reservationChanged = db.update("""
            UPDATE et_inventory_reservation SET status='RELEASED'
            WHERE order_id=? AND status='RESERVED'
            """, orderId);
        int inventoryChanged = db.update("""
            UPDATE et_ticket_tier
            SET available=available+?,reserved=reserved-?
            WHERE id=? AND reserved>=?
            """, order.quantity(), order.quantity(), order.ticketTierId(), order.quantity());
        if (orderChanged != 1 || reservationChanged != 1 || inventoryChanged != 1) {
            throw new IllegalStateException("Order close did not atomically release inventory");
        }
        assertInventoryConserved(order.ticketTierId());
        return true;
    }

    private LockedOrder lockOrder(long orderId) {
        try {
            return db.queryForObject("""
                SELECT id,user_id,ticket_tier_id,quantity,status,payment_deadline
                FROM et_order WHERE id=? FOR UPDATE
                """, (rs, row) -> new LockedOrder(rs.getLong("id"), rs.getLong("user_id"),
                    rs.getLong("ticket_tier_id"), rs.getInt("quantity"), rs.getString("status"),
                    rs.getTimestamp("payment_deadline").toInstant()), orderId);
        } catch (EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }

    private LockedReservation lockReservation(long orderId) {
        try {
            return db.queryForObject("""
                SELECT ticket_tier_id,quantity,status
                FROM et_inventory_reservation WHERE order_id=? FOR UPDATE
                """, (rs, row) -> new LockedReservation(rs.getLong("ticket_tier_id"),
                    rs.getInt("quantity"), rs.getString("status")), orderId);
        } catch (EmptyResultDataAccessException missing) {
            throw new IllegalStateException("Pending order has no inventory reservation", missing);
        }
    }

    private void lockTierInventory(long ticketTierId) {
        try {
            db.queryForObject("SELECT id FROM et_ticket_tier WHERE id=? FOR UPDATE",
                    Long.class, ticketTierId);
        } catch (EmptyResultDataAccessException missing) {
            throw new IllegalStateException("Reservation ticket tier does not exist", missing);
        }
    }

    private void assertInventoryConserved(long ticketTierId) {
        Integer violations = db.queryForObject("""
            SELECT COUNT(*) FROM et_ticket_tier
            WHERE id=? AND (available<0 OR reserved<0 OR allocated<0
                OR available+reserved+allocated<>capacity)
            """, Integer.class, ticketTierId);
        if (violations == null || violations != 0) {
            throw new IllegalStateException("Ticket inventory conservation was violated");
        }
    }

    private SellableTier lockTier(long ticketTierId) {
        try {
            return db.queryForObject("""
                SELECT e.id event_id,e.status event_status,s.id session_id,s.status session_status,
                       s.sales_start_at,s.sales_end_at,t.unit_price,t.currency,t.available,
                       t.status tier_status
                FROM et_ticket_tier t
                JOIN et_event_session s ON s.id=t.session_id
                JOIN et_event e ON e.id=s.event_id
                WHERE t.id=? FOR UPDATE
                """, (rs, row) -> new SellableTier(
                    rs.getLong("event_id"), rs.getString("event_status"),
                    rs.getLong("session_id"), rs.getString("session_status"),
                    rs.getTimestamp("sales_start_at").toInstant(),
                    rs.getTimestamp("sales_end_at").toInstant(),
                    rs.getLong("unit_price"), rs.getString("currency"),
                    rs.getInt("available"), rs.getString("tier_status")), ticketTierId);
        } catch (EmptyResultDataAccessException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket tier not found");
        }
    }

    private OrderView findByKey(long userId, String idempotencyKey) {
        List<OrderView> rows = db.query("""
            SELECT id,order_number,user_id,event_id,session_id,ticket_tier_id,quantity,
                   unit_price,total_amount,currency,status,idempotency_key,request_hash,
                   payment_deadline,close_reason,created_at
            FROM et_order WHERE user_id=? AND idempotency_key=?
            """, (rs, row) -> mapOrder(rs), userId, idempotencyKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private OrderView replay(OrderView existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different request");
        }
        return existing;
    }

    private OrderView mapOrder(ResultSet rs) throws SQLException {
        return new OrderView(rs.getLong("id"), rs.getString("order_number"), rs.getLong("user_id"),
                rs.getLong("event_id"), rs.getLong("session_id"), rs.getLong("ticket_tier_id"),
                rs.getInt("quantity"), rs.getLong("unit_price"), rs.getLong("total_amount"),
                rs.getString("currency"), rs.getString("status"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getTimestamp("payment_deadline").toInstant(),
                rs.getString("close_reason"), rs.getTimestamp("created_at").toInstant());
    }

    private Instant databaseNow() {
        Timestamp value = db.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database did not return its current time");
        }
        return value.toInstant();
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128
                || key.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 16 to 128 visible ASCII characters");
        }
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private record SellableTier(long eventId, String eventStatus, long sessionId, String sessionStatus,
            Instant salesStartAt, Instant salesEndAt, long unitPrice, String currency,
            int available, String tierStatus) {}

    private record LockedOrder(long id, long userId, long ticketTierId, int quantity, String status,
            Instant paymentDeadline) {}

    private record LockedReservation(long ticketTierId, int quantity, String status) {}


    public record OrderView(long id, String orderNumber, long userId, long eventId, long sessionId,
            long ticketTierId, int quantity, long unitPrice, long totalAmount, String currency,
            String status, @JsonIgnore String idempotencyKey, @JsonIgnore String requestHash,
            Instant paymentDeadline, String closeReason,
            Instant createdAt) {}
}
