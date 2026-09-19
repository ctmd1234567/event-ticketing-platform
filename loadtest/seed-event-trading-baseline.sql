SET @event_id = 9900070001;
SET @session_id = 9900070002;
SET @tier_id = 9900070003;
SET @capacity = COALESCE(@capacity, 100);

DELETE h FROM et_payment_history h
JOIN et_payment p ON p.id=h.payment_id
JOIN et_order o ON o.id=p.order_id
WHERE o.ticket_tier_id=@tier_id;
DELETE gr FROM sim_gateway_refund gr
JOIN sim_gateway_payment gp ON gp.payment_number=gr.payment_number
JOIN et_payment p ON p.payment_number=gp.payment_number
JOIN et_order o ON o.id=p.order_id
WHERE o.ticket_tier_id=@tier_id;
DELETE r FROM et_refund r
JOIN et_payment p ON p.id=r.payment_id
JOIN et_order o ON o.id=p.order_id
WHERE o.ticket_tier_id=@tier_id;
DELETE gp FROM sim_gateway_payment gp
JOIN et_payment p ON p.payment_number=gp.payment_number
JOIN et_order o ON o.id=p.order_id
WHERE o.ticket_tier_id=@tier_id;
DELETE p FROM et_payment p
JOIN et_order o ON o.id=p.order_id
WHERE o.ticket_tier_id=@tier_id;
DELETE FROM et_inventory_reservation WHERE ticket_tier_id=@tier_id;
DELETE FROM et_order WHERE ticket_tier_id=@tier_id;
DELETE FROM et_ticket_tier WHERE id=@tier_id;
DELETE FROM et_event_session WHERE id=@session_id;
DELETE FROM et_event WHERE id=@event_id;

INSERT INTO et_event(id,title,description,venue,status,created_by)
VALUES (@event_id,'Checklist 7 HTTP baseline','Isolated and repeatable','local-only','PUBLISHED',9900070000);
INSERT INTO et_event_session(id,event_id,name,starts_at,ends_at,sales_start_at,sales_end_at,status)
VALUES (@session_id,@event_id,'Baseline session',CURRENT_TIMESTAMP + INTERVAL 2 HOUR,
        CURRENT_TIMESTAMP + INTERVAL 4 HOUR,CURRENT_TIMESTAMP - INTERVAL 1 MINUTE,
        CURRENT_TIMESTAMP + INTERVAL 1 HOUR,'ON_SALE');
INSERT INTO et_ticket_tier(id,session_id,name,unit_price,currency,capacity,available,reserved,allocated,
                           purchase_limit_per_user,status)
VALUES (@tier_id,@session_id,'Baseline tier',8800,'CNY',@capacity,@capacity,0,0,1,'ON_SALE');

SELECT id,capacity,available,reserved,allocated,status
FROM et_ticket_tier WHERE id=@tier_id;
