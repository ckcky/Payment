DROP TABLE IF EXISTS payment_attempts;
DROP TABLE IF EXISTS payments;

CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    payment_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    attempt_seq INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    current_attempt_id BIGINT,
    failure_reason VARCHAR(255),
    query_attempts INT NOT NULL DEFAULT 0,
    entered_unknown_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX idx_payments_txn_seq ON payments (transaction_id, attempt_seq);

CREATE TABLE payment_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no VARCHAR(32) NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    attempt_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT',
    requested_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP,
    channel_reference VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    retry_count INT NOT NULL DEFAULT 0,
    error_type VARCHAR(16) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_attempts_channel_reference UNIQUE (channel_reference)
);

-- Feature 015 / P3：退款域并入 payment-service（表随域迁入同一 H2 库）
DROP TABLE IF EXISTS refund_items;
DROP TABLE IF EXISTS refund_intake_locks;
DROP TABLE IF EXISTS refund_post_process_attempts;
DROP TABLE IF EXISTS refunds;

CREATE TABLE refunds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(32) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    payment_no VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_refunds_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uk_refunds_refund_no UNIQUE (refund_no)
);

CREATE TABLE refund_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    order_item_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1
);

CREATE TABLE refund_intake_locks (
    payment_no VARCHAR(32) NOT NULL PRIMARY KEY
);

CREATE TABLE refund_post_process_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    target VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(512),
    attempt_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_rppa_refund_target UNIQUE (refund_id, target)
);
