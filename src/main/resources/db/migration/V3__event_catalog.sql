CREATE TABLE et_event (
    id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    venue VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT ck_event_status CHECK (status IN ('DRAFT','PUBLISHED','OFF_SALE','ENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE et_event_session (
    id BIGINT NOT NULL PRIMARY KEY,
    event_id BIGINT NOT NULL,
    name VARCHAR(160) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    sales_start_at TIMESTAMP NOT NULL,
    sales_end_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_session_event FOREIGN KEY(event_id) REFERENCES et_event(id),
    CONSTRAINT ck_session_status CHECK (status IN ('DRAFT','ON_SALE','OFF_SALE','ENDED')),
    CONSTRAINT ck_session_time CHECK (starts_at < ends_at),
    CONSTRAINT ck_session_sales_time CHECK (sales_start_at < sales_end_at AND sales_end_at <= starts_at),
    INDEX ix_session_event(event_id),
    INDEX ix_session_sales(status, sales_start_at, sales_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE et_ticket_tier (
    id BIGINT NOT NULL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    unit_price BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    capacity INT NOT NULL,
    available INT NOT NULL,
    reserved INT NOT NULL DEFAULT 0,
    allocated INT NOT NULL DEFAULT 0,
    purchase_limit_per_user INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_tier_session FOREIGN KEY(session_id) REFERENCES et_event_session(id),
    CONSTRAINT ck_tier_status CHECK (status IN ('DRAFT','ON_SALE','OFF_SALE','SOLD_OUT')),
    CONSTRAINT ck_tier_price CHECK (unit_price >= 0),
    CONSTRAINT ck_tier_capacity CHECK (
        capacity > 0 AND available >= 0 AND reserved >= 0 AND allocated >= 0
        AND available + reserved + allocated = capacity
    ),
    CONSTRAINT ck_tier_purchase_limit CHECK (purchase_limit_per_user = 1),
    INDEX ix_tier_session(session_id),
    INDEX ix_tier_sale(status, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
