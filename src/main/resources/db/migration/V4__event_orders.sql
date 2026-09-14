CREATE TABLE et_order (
    id BIGINT NOT NULL PRIMARY KEY,
    order_number VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    ticket_tier_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price BIGINT NOT NULL,
    total_amount BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(160) NOT NULL,
    payment_deadline TIMESTAMP NOT NULL,
    close_reason VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_event_order_number UNIQUE(order_number),
    CONSTRAINT uk_event_order_idempotency UNIQUE(user_id, idempotency_key),
    CONSTRAINT uk_event_order_purchase_limit UNIQUE(user_id, ticket_tier_id),
    CONSTRAINT fk_event_order_event FOREIGN KEY(event_id) REFERENCES et_event(id),
    CONSTRAINT fk_event_order_session FOREIGN KEY(session_id) REFERENCES et_event_session(id),
    CONSTRAINT fk_event_order_tier FOREIGN KEY(ticket_tier_id) REFERENCES et_ticket_tier(id),
    CONSTRAINT ck_event_order_quantity CHECK (quantity = 1),
    CONSTRAINT ck_event_order_money CHECK (
        unit_price >= 0 AND total_amount = unit_price * quantity AND currency = 'CNY'
    ),
    CONSTRAINT ck_event_order_status CHECK (status IN ('PENDING_PAYMENT','PAID','FULFILLED','CLOSED','REFUNDING','REFUNDED')),
    INDEX ix_event_order_user_created(user_id, created_at),
    INDEX ix_event_order_status_deadline(status, payment_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE et_inventory_reservation (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    ticket_tier_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_reservation_order UNIQUE(order_id),
    CONSTRAINT fk_reservation_order FOREIGN KEY(order_id) REFERENCES et_order(id),
    CONSTRAINT fk_reservation_tier FOREIGN KEY(ticket_tier_id) REFERENCES et_ticket_tier(id),
    CONSTRAINT ck_reservation_quantity CHECK (quantity = 1),
    CONSTRAINT ck_reservation_status CHECK (status IN ('RESERVED','CONFIRMED','RELEASED')),
    INDEX ix_reservation_tier_status(ticket_tier_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
