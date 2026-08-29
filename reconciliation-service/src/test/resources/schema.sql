DROP TABLE IF EXISTS reconciliation_differences;
DROP TABLE IF EXISTS reconciliation_batches;

CREATE TABLE reconciliation_batches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    matches_json TEXT,
    differences_json TEXT,
    closed_at DATETIME NULL,
    closed_by VARCHAR(64) NULL,
    statement_source VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_reconciliation_batches_period UNIQUE (period)
);

CREATE TABLE reconciliation_differences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    reference VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    platform_amount_minor BIGINT,
    channel_amount_minor BIGINT,
    platform_status VARCHAR(32),
    channel_status VARCHAR(32),
    resolution_status VARCHAR(32),
    resolution_note VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1
);
