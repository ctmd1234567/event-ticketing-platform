ALTER TABLE tb_voucher_order
    ADD CONSTRAINT uk_order_user_voucher UNIQUE(user_id, voucher_id);

CREATE TABLE tb_order_request (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    voucher_id BIGINT NOT NULL,
    stock_bucket SMALLINT UNSIGNED,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_request_user_voucher UNIQUE(user_id, voucher_id)
) ENGINE=InnoDB;

CREATE TABLE tb_outbox_event (
    id BIGINT NOT NULL PRIMARY KEY,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(36),
    last_error VARCHAR(255),
    INDEX ix_outbox_due(completed, next_attempt),
    INDEX ix_outbox_lease(lease_owner)
) ENGINE=InnoDB;

CREATE TABLE tb_seckill_voucher_bucket (
    voucher_id BIGINT UNSIGNED NOT NULL,
    bucket_id SMALLINT UNSIGNED NOT NULL,
    stock INT NOT NULL,
    PRIMARY KEY(voucher_id, bucket_id),
    CONSTRAINT ck_bucket_stock_nonnegative CHECK (stock >= 0)
) ENGINE=InnoDB;
