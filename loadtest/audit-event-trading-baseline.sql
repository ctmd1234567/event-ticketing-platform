SET @tier_id = 9900070003;

SELECT
  t.capacity,
  t.available,
  t.reserved,
  t.allocated,
  COUNT(DISTINCT o.id) AS order_count,
  COUNT(DISTINCT CASE WHEN o.status='PENDING_PAYMENT' THEN o.id END) AS pending_order_count,
  COUNT(DISTINCT o.user_id) AS distinct_buyer_count,
  COUNT(DISTINCT ir.id) AS reservation_count,
  COUNT(DISTINCT CASE WHEN ir.status='RESERVED' THEN ir.id END) AS reserved_reservation_count,
  CASE WHEN t.available+t.reserved+t.allocated=t.capacity THEN 1 ELSE 0 END AS inventory_conserved,
  CASE WHEN t.available >= 0 AND t.reserved >= 0 AND t.allocated >= 0 THEN 1 ELSE 0 END AS inventory_nonnegative,
  CASE WHEN COUNT(DISTINCT o.id)=COUNT(DISTINCT ir.order_id)
         AND COUNT(DISTINCT CASE
               WHEN ir.ticket_tier_id=o.ticket_tier_id
                AND ir.quantity=o.quantity
                AND o.status='PENDING_PAYMENT'
                AND ir.status='RESERVED'
               THEN o.id END)=COUNT(DISTINCT o.id)
       THEN 1 ELSE 0 END AS reservation_order_match,
  CASE WHEN COUNT(DISTINCT o.id)=COUNT(DISTINCT o.user_id)
         AND COUNT(DISTINCT o.id)=COUNT(DISTINCT CONCAT(o.user_id, ':', o.idempotency_key))
       THEN 1 ELSE 0 END AS no_duplicate_effective_order,
  CASE WHEN COUNT(DISTINCT ir.id)=COUNT(DISTINCT ir.order_id)
         AND COALESCE(SUM(ir.quantity),0)=COUNT(DISTINCT o.id)
       THEN 1 ELSE 0 END AS no_duplicate_reservation,
  CASE WHEN COUNT(DISTINCT o.id)=@expected_success
         AND COUNT(DISTINCT CASE WHEN o.status='PENDING_PAYMENT' THEN o.id END)=@expected_success
         AND COUNT(DISTINCT o.user_id)=@expected_success
         AND COUNT(DISTINCT ir.id)=@expected_success
         AND COUNT(DISTINCT CASE WHEN ir.status='RESERVED' THEN ir.id END)=@expected_success
         AND t.available=t.capacity-@expected_success
         AND t.reserved=@expected_success
         AND t.allocated=0
       THEN 1 ELSE 0 END AS exact_result
FROM et_ticket_tier t
LEFT JOIN et_order o ON o.ticket_tier_id=t.id
LEFT JOIN et_inventory_reservation ir ON ir.order_id=o.id
WHERE t.id=@tier_id
GROUP BY t.id,t.capacity,t.available,t.reserved,t.allocated;
