CREATE TABLE et_outbox_event (
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    publish_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until TIMESTAMP NULL,
    published_at TIMESTAMP NULL,
    last_error VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_et_outbox_transition UNIQUE(event_type, aggregate_id),
    CONSTRAINT fk_et_outbox_order FOREIGN KEY(aggregate_id) REFERENCES et_order(id),
    CONSTRAINT ck_et_outbox_type CHECK (event_type IN ('ORDER_PAID','ORDER_CLOSED')),
    CONSTRAINT ck_et_outbox_status CHECK (publish_status IN ('PENDING','PROCESSING','PUBLISHED','MANUAL_REQUIRED')),
    CONSTRAINT ck_et_outbox_attempts CHECK (attempts >= 0),
    INDEX ix_et_outbox_due(publish_status, next_attempt_at, event_id),
    INDEX ix_et_outbox_lease(publish_status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE et_notification (
    id BIGINT NOT NULL PRIMARY KEY,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    notification_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_et_notification_event UNIQUE(event_id),
    CONSTRAINT fk_et_notification_event FOREIGN KEY(event_id) REFERENCES et_outbox_event(event_id),
    CONSTRAINT fk_et_notification_order FOREIGN KEY(order_id) REFERENCES et_order(id),
    INDEX ix_et_notification_user_created(user_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
