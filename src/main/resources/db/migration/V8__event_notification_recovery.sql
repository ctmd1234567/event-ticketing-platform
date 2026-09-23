CREATE TABLE et_notification_failure (
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    original_body BLOB NOT NULL,
    failure_kind VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(255) NOT NULL,
    failure_count INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'FAILED',
    lease_until TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT ck_et_notification_failure_status CHECK (status IN ('FAILED','REDRIVING','REDRIVEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE et_notification_redrive (
    id BIGINT NOT NULL PRIMARY KEY,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    action VARCHAR(24) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    detail VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX ix_et_notification_redrive_event(event_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
