-- 审计四核对 + 挂账调账 Schema（spec 017 / ADR-0065，Database-per-Service：reconciliation 库自有表）。
-- 幂等可重复执行；不改动既有 reconciliation_batches / reconciliation_differences。

USE `reconciliation`;

-- 审计批次（四核对作业）：period+scope 唯一 = 幂等键，重跑即回查。
CREATE TABLE IF NOT EXISTS audit_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(32) NOT NULL COMMENT '业务单号 AB+雪花（ADR-0062）',
    period VARCHAR(32) NOT NULL,
    scope VARCHAR(16) NOT NULL COMMENT 'CERTIFICATE|LEDGER|REAL|REPORT|ALL',
    status VARCHAR(16) NOT NULL COMMENT 'PROCESSING|BALANCED|HAS_DIFFERENCE|RECHECKING|CLOSED',
    checked_count INT NOT NULL DEFAULT 0,
    difference_count INT NOT NULL DEFAULT 0,
    suspended_amount_minor BIGINT NOT NULL DEFAULT 0,
    adjusted_amount_minor BIGINT NOT NULL DEFAULT 0,
    triggered_by VARCHAR(64) NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_batches_batch_no (batch_no),
    UNIQUE KEY uk_audit_batches_period_scope (period, scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 审计差异：kind 11 类 + severity 三级 + 处置状态机（PENDING→SUSPENDED→ADJUSTED→VERIFIED→RESOLVED）。
CREATE TABLE IF NOT EXISTS audit_differences (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    severity VARCHAR(8) NOT NULL COMMENT 'BLOCKER|MAJOR|MINOR',
    source_type VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    reference VARCHAR(128) NULL,
    expected_amount_minor BIGINT NULL,
    actual_amount_minor BIGINT NULL,
    currency VARCHAR(8) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    suspended_amount_minor BIGINT NOT NULL DEFAULT 0,
    adjusted_amount_minor BIGINT NOT NULL DEFAULT 0,
    transferred_out_minor BIGINT NOT NULL DEFAULT 0 COMMENT '累计从 SUSPENSE 转出（TRANSFER）',
    detail VARCHAR(512) NULL,
    resolution_note VARCHAR(255) NULL,
    resolved_by VARCHAR(64) NULL,
    resolved_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_audit_diff_batch (batch_id),
    KEY idx_audit_diff_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 挂账/调账台账：每一笔处置（SUSPEND/SUPPLEMENT/REVERSE/CORRECT/TRANSFER/WRITE_OFF）留痕。
CREATE TABLE IF NOT EXISTS audit_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    adjust_no VARCHAR(32) NOT NULL COMMENT '业务单号 AD+雪花（ADR-0062）',
    batch_id BIGINT NOT NULL,
    difference_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    debit_account_code VARCHAR(32) NOT NULL,
    credit_account_code VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    posting_no VARCHAR(32) NULL COMMENT 'ledger 侧 ADJUSTMENT posting 单号',
    status VARCHAR(16) NOT NULL DEFAULT 'POSTED' COMMENT 'POSTED|REVERSED',
    operator VARCHAR(64) NOT NULL,
    reviewer VARCHAR(64) NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_adjustments_adjust_no (adjust_no),
    KEY idx_adj_diff (difference_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
