ALTER TABLE et_order
    ADD COLUMN expiry_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN expiry_next_attempt_at TIMESTAMP NULL,
    ADD COLUMN expiry_last_error VARCHAR(255) NULL;
