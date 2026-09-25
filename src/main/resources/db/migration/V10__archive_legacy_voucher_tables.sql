-- Retire the Voucher experiment without discarding existing rows. Event trading uses et_*.
-- Keep the original V1/V2 migrations immutable for Flyway validation and fresh installs.
RENAME TABLE
    tb_voucher TO archive_legacy_voucher,
    tb_seckill_voucher TO archive_legacy_seckill_voucher,
    tb_voucher_order TO archive_legacy_voucher_order,
    tb_order_request TO archive_legacy_order_request,
    tb_outbox_event TO archive_legacy_outbox_event,
    tb_seckill_voucher_bucket TO archive_legacy_seckill_voucher_bucket;
