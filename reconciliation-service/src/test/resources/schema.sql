DROP TABLE IF EXISTS reconciliation_differences;
DROP TABLE IF EXISTS reconciliation_batches;

CREATE TABLE reconciliation_batches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period VARCHAR(32) NOT NULL,
    batch_no VARCHAR(32) NOT NULL,
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

-- ==== spec 017：审计四核对 + 挂账调账（H2 MODE=MySQL）====
CREATE TABLE IF NOT EXISTS audit_batches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(32) NOT NULL,
    period VARCHAR(32) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    checked_count INT NOT NULL DEFAULT 0,
    difference_count INT NOT NULL DEFAULT 0,
    suspended_amount_minor BIGINT NOT NULL DEFAULT 0,
    adjusted_amount_minor BIGINT NOT NULL DEFAULT 0,
    triggered_by VARCHAR(64) NULL,
    started_at VARCHAR(64) NULL,
    finished_at VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_audit_batches_batch_no UNIQUE (batch_no),
    CONSTRAINT uk_audit_batches_period_scope UNIQUE (period, scope)
);

CREATE TABLE IF NOT EXISTS audit_differences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    reference VARCHAR(128) NULL,
    expected_amount_minor BIGINT NULL,
    actual_amount_minor BIGINT NULL,
    currency VARCHAR(8) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    suspended_amount_minor BIGINT NOT NULL DEFAULT 0,
    adjusted_amount_minor BIGINT NOT NULL DEFAULT 0,
    transferred_out_minor BIGINT NOT NULL DEFAULT 0,
    detail VARCHAR(512) NULL,
    resolution_note VARCHAR(255) NULL,
    resolved_by VARCHAR(64) NULL,
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS audit_adjustments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    adjust_no VARCHAR(32) NOT NULL,
    batch_id BIGINT NOT NULL,
    difference_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    debit_account_code VARCHAR(32) NOT NULL,
    credit_account_code VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    posting_no VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    operator VARCHAR(64) NOT NULL,
    reviewer VARCHAR(64) NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_adjustments_adjust_no UNIQUE (adjust_no)
);
