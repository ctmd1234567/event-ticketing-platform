-- Supply the same @scenario_base used for seed-event-v2.sql.
SELECT t.id AS tier_id,t.capacity,t.available,t.reserved,t.allocated,
       COUNT(DISTINCT o.id) AS orders,
       COUNT(DISTINCT CASE WHEN o.status='PENDING_PAYMENT' THEN o.id END) AS pending_orders,
       COUNT(DISTINCT r.id) AS reservations,
       COUNT(DISTINCT CASE WHEN r.status='RESERVED' THEN r.id END) AS reserved_reservations,
       t.available+t.reserved+t.allocated=t.capacity AS conserved,
       t.available>=0 AND t.reserved>=0 AND t.allocated>=0 AS nonnegative,
       COUNT(DISTINCT o.id)=COUNT(DISTINCT r.order_id) AS order_reservation_match,
       COUNT(DISTINCT o.id)=COUNT(DISTINCT o.user_id) AS unique_buyers
FROM et_ticket_tier t
LEFT JOIN et_order o ON o.ticket_tier_id=t.id
LEFT JOIN et_inventory_reservation r ON r.order_id=o.id
WHERE t.id BETWEEN @scenario_base+3 AND @scenario_base+5
GROUP BY t.id,t.capacity,t.available,t.reserved,t.allocated ORDER BY t.id;

-- Since V10, the original experiment rows remain under archive_legacy_* names.
SELECT COUNT(*) AS legacy_requests FROM archive_legacy_order_request;
SELECT COUNT(*) AS legacy_orders FROM archive_legacy_voucher_order;
SELECT COUNT(*) AS legacy_outbox FROM archive_legacy_outbox_event;
