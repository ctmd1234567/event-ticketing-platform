SET @tier_id = 9900070003;

SELECT
  t.capacity,
  t.available,
  t.reserved,
  t.allocated,
  COUNT(DISTINCT o.id) AS order_count,
  COUNT(DISTINCT CASE WHEN o.status='PAID' THEN o.id END) AS paid_order_count,
  COUNT(DISTINCT p.id) AS payment_count,
  COUNT(DISTINCT CASE WHEN p.status='SUCCEEDED' THEN p.id END) AS succeeded_payment_count,
  COUNT(DISTINCT gp.payment_number) AS provider_charge_effects,
  COUNT(DISTINCT CASE WHEN r.id IS NOT NULL THEN r.id END) AS refund_count,
  CASE WHEN t.available+t.reserved+t.allocated=t.capacity THEN 1 ELSE 0 END AS inventory_conserved,
  CASE WHEN t.available >= 0 AND t.reserved >= 0 AND t.allocated >= 0 THEN 1 ELSE 0 END AS inventory_nonnegative,
  CASE WHEN COUNT(DISTINCT o.id)=COUNT(DISTINCT ir.order_id)
         AND COUNT(DISTINCT CASE
               WHEN ir.ticket_tier_id=o.ticket_tier_id
                AND ir.quantity=o.quantity
                AND ((o.status='PAID' AND ir.status='CONFIRMED')
                  OR (o.status='PENDING_PAYMENT' AND ir.status='RESERVED')
                  OR (o.status='CLOSED' AND ir.status='RELEASED'))
               THEN o.id END)=COUNT(DISTINCT o.id)
       THEN 1 ELSE 0 END AS reservation_order_match,
  CASE WHEN COUNT(DISTINCT o.id)=COUNT(DISTINCT CONCAT(o.user_id, ':', o.idempotency_key))
       THEN 1 ELSE 0 END AS no_duplicate_idempotency_result,
  CASE WHEN COUNT(DISTINCT o.id)=@expected
         AND COUNT(DISTINCT CASE WHEN o.status='PAID' THEN o.id END)=@expected
         AND COUNT(DISTINCT p.id)=@expected
         AND COUNT(DISTINCT CASE WHEN p.status='SUCCEEDED' THEN p.id END)=@expected
         AND COUNT(DISTINCT gp.payment_number)=@expected
         AND COUNT(DISTINCT o.user_id)=@expected
         AND t.available=t.capacity-@expected
         AND t.reserved=0 AND t.allocated=@expected
         AND COUNT(DISTINCT CASE WHEN r.id IS NOT NULL THEN r.id END)=0
         AND COUNT(DISTINCT ir.order_id)=@expected
         AND COUNT(DISTINCT CASE WHEN ir.status='CONFIRMED' THEN ir.order_id END)=@expected
       THEN 1 ELSE 0 END AS exact_result
FROM et_ticket_tier t
LEFT JOIN et_order o ON o.ticket_tier_id=t.id
LEFT JOIN et_inventory_reservation ir ON ir.order_id=o.id
LEFT JOIN et_payment p ON p.order_id=o.id
LEFT JOIN sim_gateway_payment gp ON gp.payment_number=p.payment_number
LEFT JOIN et_refund r ON r.payment_id=p.id
WHERE t.id=@tier_id
GROUP BY t.id,t.capacity,t.available,t.reserved,t.allocated;
