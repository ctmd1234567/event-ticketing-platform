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
