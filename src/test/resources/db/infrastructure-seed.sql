INSERT INTO tb_shop_type(id, name, icon, sort) VALUES (1, 'Test type', '', 1);
INSERT INTO tb_shop(id, name, type_id, images, address, x, y, sold, comments, score)
VALUES (1, 'Test shop', 1, '', 'Test address', 1, 1, 0, 0, 0);
INSERT INTO tb_user(id, phone, nick_name) VALUES (900001, '13900000001', 'integration');
INSERT INTO tb_voucher(id, shop_id, title, pay_value, actual_value, type, status)
VALUES (900001, 1, 'Integration voucher', 1, 2, 1, 1);
INSERT INTO tb_seckill_voucher(voucher_id, stock, begin_time, end_time)
VALUES (900001, 2, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY));
INSERT INTO tb_seckill_voucher_bucket(voucher_id, bucket_id, stock)
VALUES (900001, 0, 1), (900001, 1, 1);

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
