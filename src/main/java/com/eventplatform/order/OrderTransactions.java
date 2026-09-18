package com.eventplatform.order;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

@Service
@org.springframework.context.annotation.Profile("legacy-experiment")
public class OrderTransactions {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final StockBuckets stockBuckets;
    private final OrderPerformance performance;

    public OrderTransactions(JdbcTemplate db, PlatformTransactionManager transactionManager,
            StockBuckets stockBuckets, OrderPerformance performance) {
        this.db = db;
        this.transactions = new TransactionTemplate(transactionManager);
        this.stockBuckets = stockBuckets;
        this.performance = performance;
    }

    /** A durable reservation, inventory change and outgoing event share one database transaction. */
    public long reserve(long userId, long voucherId) {
        return performance.protect(() -> reserveProtected(userId, voucherId));
    }

    private long reserveProtected(long userId, long voucherId) {
        Long existing = findExisting(userId, voucherId);
        if (existing != null) return existing;
        try {
            Long reserved = transactions.execute(status -> reserveNew(userId, voucherId));
            if (reserved == null) throw new IllegalStateException("Reservation transaction returned no order id");
            return reserved;
        } catch (DuplicateKeyException duplicate) {
            // Concurrent retries can both pass the fast read. The losing transaction rolls back
            // its stock decrement before this lookup returns the winner's durable request id.
            existing = findExisting(userId, voucherId);
            if (existing != null) return existing;
            throw duplicate;
        }
    }

    private long reserveNew(long userId, long voucherId) {
        long id = IdWorker.getId();
        int bucket = stockBuckets.reserve(userId, voucherId);
        db.update("INSERT INTO tb_order_request(id,user_id,voucher_id,stock_bucket,state) VALUES (?,?,?,?,'PENDING')",
                id,userId,voucherId,bucket);
        db.update("INSERT INTO tb_outbox_event(id,next_attempt) VALUES (?,CURRENT_TIMESTAMP)", id);
        return id;
    }

    private Long findExisting(long userId, long voucherId) {
        // One query covers both the durable request and legacy/final order paths. New buyers no
        // longer pay a second database round-trip when neither row exists.
        List<Long> existing = db.queryForList("""
            SELECT id FROM tb_order_request WHERE user_id=? AND voucher_id=?
            UNION ALL SELECT id FROM tb_voucher_order WHERE user_id=? AND voucher_id=? LIMIT 1
            """, Long.class, userId, voucherId, userId, voucherId);
        if (!existing.isEmpty()) return existing.getFirst();
        return null;
    }

    /** Lock and state check make redelivery a no-op; commit before acknowledging the message. */
    @Transactional
    public void fulfill(long id) {
        fulfillBatch(List.of(id));
    }

    @Transactional
    public void fulfillBatch(List<Long> ids) {
        List<Long> orderedIds = ids.stream().distinct().sorted().toList();
        if (orderedIds.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(orderedIds.size(), "?"));
        var requests = db.queryForList("SELECT id,user_id,voucher_id,state,created_at FROM tb_order_request "
                + "WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE", orderedIds.toArray());
        if (requests.size() != orderedIds.size()) throw new IllegalArgumentException("Unknown order request");
        List<Map<String,Object>> pending = requests.stream()
                .filter(request -> !"COMPLETED".equals(request.get("state"))).toList();
        if (pending.isEmpty()) return;

        String values = String.join(",", Collections.nCopies(pending.size(), "(?,?,?)"));
        Object[] orderArguments = new Object[pending.size() * 3];
        List<Long> pendingIds = new java.util.ArrayList<>(pending.size());
        for (int index = 0; index < pending.size(); index++) {
            Map<String,Object> request = pending.get(index);
            long id = ((Number) request.get("id")).longValue();
            pendingIds.add(id);
            orderArguments[index * 3] = id;
            orderArguments[index * 3 + 1] = ((Number) request.get("user_id")).longValue();
            orderArguments[index * 3 + 2] = ((Number) request.get("voucher_id")).longValue();
        }
        db.update("INSERT INTO tb_voucher_order(id,user_id,voucher_id) VALUES " + values, orderArguments);
        String pendingPlaceholders = String.join(",", Collections.nCopies(pendingIds.size(), "?"));
        Object[] pendingArguments = pendingIds.toArray();
        db.update("UPDATE tb_order_request SET state='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id IN ("
                + pendingPlaceholders + ")", pendingArguments);
        db.update("UPDATE tb_outbox_event SET completed=TRUE,lease_owner=NULL WHERE id IN ("
                + pendingPlaceholders + ")", pendingArguments);
        Instant completedAt = Instant.now();
        for (Map<String,Object> request : pending) {
            performance.completed(Duration.between(((Timestamp) request.get("created_at")).toInstant(), completedAt));
        }
    }

    public Map<String,Object> status(long id, long userId) {
        var rows = db.queryForList("SELECT id,state,created_at,updated_at FROM tb_order_request WHERE id=? AND user_id=?", id,userId);
        if (!rows.isEmpty()) return rows.getFirst();
        var orders = db.queryForList("SELECT id FROM tb_voucher_order WHERE id=? AND user_id=?", id,userId);
        if (!orders.isEmpty()) return Map.of("id",id,"state","COMPLETED");
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
    }
}
