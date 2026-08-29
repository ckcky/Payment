DROP TABLE IF EXISTS settlement_items;
DROP TABLE IF EXISTS settlement_batches;

CREATE TABLE settlement_batches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(32) NOT NULL,
    period VARCHAR(32) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    income_minor BIGINT NOT NULL,
    refund_minor BIGINT NOT NULL,
    adjustment_minor BIGINT NOT NULL,
    net_minor BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    fact_count INT NOT NULL DEFAULT 0,
    source_period VARCHAR(32),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_settlement_batches_merchant_period UNIQUE (merchant_id, period),
    CONSTRAINT uk_settlement_batches_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE settlement_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    reference VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1
);

CREATE TABLE settlement_adjustments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    merchant_id VARCHAR(32) NOT NULL,
    period VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    direction VARCHAR(16) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_settlement_adjustments_idem UNIQUE (idempotency_key),
    KEY idx_settlement_adjustments_scope (merchant_id, period, status)
);
