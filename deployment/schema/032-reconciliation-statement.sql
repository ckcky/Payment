-- =============================================================================
-- Feature 032 迁移：对账域实账化（spec 032 §9 变更集，H-032-2）
--
-- 作用（存量库就地演进，不留两套模型）：
--   ① reconciliation_batches 加 channel_code / import_id；
--      唯一约束 uk_reconciliation_batches_period → uk_reconciliation_batches_identity
--      （uk(channel_code, period, import_id)——CLOSED 批次允许同周期新导入并存，更正账单可重对）；
--   ② 三新表 statement_imports / statement_lines / reconciliation_differences（与
--      07-reconciliation-schema.sql 全量文件完全一致，CREATE TABLE IF NOT EXISTS 可重放）；
--   ③ audit_differences 加 import_id；audit_adjustments 加 diff_no、batch_id/difference_id 放宽可空
--      （spec 032 自动挂账台账：非审计批次内处置经 diff_no 回溯 recon 差异）。
--
-- 幂等可重放：沿用 015/018/019/030/031 模式（information_schema 守卫 + PREPARE 动态 SQL）。
-- 禁用 `ADD COLUMN IF NOT EXISTS`（MariaDB 方言，MySQL 8 报错——016 教训）。
-- 全新库无需执行本脚本（07/10 全量文件已是目标形态；reset.sh 全量重放时本脚本
-- 各项守卫条件均为假，整体为无害 no-op）。
-- =============================================================================

USE `reconciliation`;
SET @db = DATABASE();

-- ---------------------------------------------------------------------------
-- ① reconciliation_batches 增列 + 唯一约束切换
-- ---------------------------------------------------------------------------

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'reconciliation_batches' AND COLUMN_NAME = 'channel_code');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE reconciliation_batches ADD COLUMN channel_code VARCHAR(16) NOT NULL DEFAULT ''MOCK'' COMMENT ''对账渠道（spec 032 §9 ④；历史批次缺省 MOCK）'' AFTER source',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'reconciliation_batches' AND COLUMN_NAME = 'import_id');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE reconciliation_batches ADD COLUMN import_id BIGINT NULL COMMENT ''账单导入批次 id（spec 032；NULL = 032 前历史批次）'' AFTER channel_code',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 唯一约束切换：uk(period) 退役、uk(channel_code, period, import_id) 上线（各守卫一次）。
SET @has_old_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'reconciliation_batches'
      AND INDEX_NAME = 'uk_reconciliation_batches_period');
SET @sql = IF(@has_old_idx > 0,
    'ALTER TABLE reconciliation_batches DROP INDEX uk_reconciliation_batches_period',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_new_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'reconciliation_batches'
      AND INDEX_NAME = 'uk_reconciliation_batches_identity');
SET @sql = IF(@has_new_idx = 0,
    'ALTER TABLE reconciliation_batches ADD UNIQUE KEY uk_reconciliation_batches_identity (channel_code, period, import_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- ② 三新表（DDL 与 07-reconciliation-schema.sql 完全一致，IF NOT EXISTS 可重放）
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS statement_imports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    import_no VARCHAR(32) NOT NULL COMMENT '业务单号 SI+雪花（ADR-0062）',
    channel_code VARCHAR(16) NOT NULL,
    period VARCHAR(32) NOT NULL COMMENT '业务周期串（plan §2.1：VARCHAR(32) 超集，兼容 YYYY-MM 与 YYYY-MM-DD）',
    source_type VARCHAR(8) NOT NULL DEFAULT 'FILE' COMMENT 'FILE（本轮）/ API（H-032-5，本轮不做）',
    content_fingerprint CHAR(64) NOT NULL COMMENT 'SHA-256(规范化字节流)',
    row_count INT NOT NULL DEFAULT 0,
    status VARCHAR(12) NOT NULL COMMENT 'RECEIVED|NORMALIZED|REJECTED',
    error_reason VARCHAR(255) NULL,
    imported_by VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_import_no (import_no),
    UNIQUE KEY uk_import_identity (channel_code, period, content_fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS statement_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    import_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    channel_code VARCHAR(16) NOT NULL,
    channel_txn_no VARCHAR(64) NULL,
    reference_type VARCHAR(12) NOT NULL COMMENT 'PAYMENT/REFUND/SETTLEMENT/FEE/UNKNOWN（不可识别行存 UNKNOWN）',
    reference VARCHAR(64) NULL,
    reference_kind VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'CHANNEL_TXN|PLATFORM_NO|NONE',
    merchant_id VARCHAR(32) NULL,
    amount_minor BIGINT NOT NULL,
    fee_minor BIGINT NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    occurred_at DATETIME NULL,
    raw_text TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_line (import_id, line_no),
    UNIQUE KEY uk_channel_txn (import_id, channel_txn_no, reference_type),
    KEY idx_line_ref (reference_type, reference, merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reconciliation_differences (
    id BIGINT NOT NULL AUTO_INCREMENT,
    diff_no VARCHAR(32) NOT NULL COMMENT '业务单号 RD+雪花（ADR-0062）',
    batch_id BIGINT NOT NULL,
    import_id BIGINT NULL,
    period VARCHAR(32) NOT NULL,
    merchant_id VARCHAR(32) NULL,
    channel_code VARCHAR(16) NULL,
    kind VARCHAR(24) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    reference_type VARCHAR(12) NULL,
    reference VARCHAR(128) NULL,
    expected_amount_minor BIGINT NULL,
    actual_amount_minor BIGINT NULL,
    fee_amount_minor BIGINT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SUSPENDED|ADJUST_FAILED|RESOLVED',
    disposition_ref VARCHAR(32) NULL COMMENT '关联 audit_adjustments.adjust_no / 处置单',
    resolution_note VARCHAR(255) NULL,
    resolved_by VARCHAR(64) NULL,
    resolved_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_diff_no (diff_no),
    UNIQUE KEY uk_diff_identity (period, kind, reference_type, reference),
    KEY idx_diff_status (status, period),
    KEY idx_diff_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- ③ audit 侧增列（spec 032 §9 ④ / plan §2.7）
-- ---------------------------------------------------------------------------

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'audit_differences' AND COLUMN_NAME = 'import_id');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE audit_differences ADD COLUMN import_id BIGINT NULL COMMENT ''账单导入批次 id（spec 032 §9 ④：账实核对可回溯到账单行）'' AFTER batch_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'audit_adjustments' AND COLUMN_NAME = 'diff_no');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE audit_adjustments ADD COLUMN diff_no VARCHAR(32) NULL COMMENT ''对账差异记录号 RD+雪花（spec 032 T23：自动处置回溯 recon diffNo）'' AFTER difference_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- batch_id / difference_id 放宽可空（存量库 MODIFY 幂等可重放；spec 032 自动挂账台账）
SET @sql = 'ALTER TABLE audit_adjustments
  MODIFY COLUMN batch_id BIGINT NULL COMMENT ''NULL = 非批次内处置（spec 032 自动挂账，经 diff_no 回溯对账差异）''';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = 'ALTER TABLE audit_adjustments
  MODIFY COLUMN difference_id BIGINT NULL COMMENT ''NULL = 审计差异外处置（spec 032 自动挂账）''';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT '032-reconciliation-statement migration applied' AS result;
