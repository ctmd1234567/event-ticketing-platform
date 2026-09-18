INSERT INTO et_event(id, title, description, venue, status, created_by)
VALUES (910001, 'Integration event', 'Isolated integration fixture', 'Test hall', 'PUBLISHED', 900001);

INSERT INTO et_event_session(
    id, event_id, name, starts_at, ends_at, sales_start_at, sales_end_at, status)
VALUES (
    910001, 910001, 'Integration session',
    DATE_ADD(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY),
    DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), 'ON_SALE');

INSERT INTO et_ticket_tier(
    id, session_id, name, unit_price, currency, capacity, available, reserved, allocated,
    purchase_limit_per_user, status)
VALUES (910001, 910001, 'Integration tier', 4750, 'CNY', 2, 2, 0, 0, 1, 'ON_SALE');
