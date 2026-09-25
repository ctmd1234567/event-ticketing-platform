-- Read-only SQL inspection against a populated isolated test database.
-- Supply @scenario_base from an already seeded and exercised fixture.
SELECT COUNT(*) AS total_orders FROM et_order;
SELECT COUNT(*) AS total_outbox FROM et_outbox_event;

SET @sample_user = (SELECT user_id FROM et_order
                    WHERE ticket_tier_id=@scenario_base+3 LIMIT 1);
SET @sample_key = (SELECT idempotency_key FROM et_order
                   WHERE ticket_tier_id=@scenario_base+3 LIMIT 1);

EXPLAIN ANALYZE SELECT id,status,request_hash FROM et_order
WHERE user_id=@sample_user AND idempotency_key=@sample_key;

EXPLAIN ANALYZE SELECT id FROM et_order
WHERE status='PENDING_PAYMENT' AND payment_deadline<=CURRENT_TIMESTAMP
  AND (expiry_next_attempt_at IS NULL OR expiry_next_attempt_at<=CURRENT_TIMESTAMP)
ORDER BY payment_deadline,id LIMIT 200;

EXPLAIN SELECT id FROM et_ticket_tier WHERE id=@scenario_base+3 FOR UPDATE;

SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';
SELECT COUNT(*) AS current_innodb_lock_waits FROM performance_schema.data_lock_waits;
