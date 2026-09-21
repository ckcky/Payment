-- 对账服务自有 Schema（Database-per-Service）：reconciliation_batches + 账单导入三表。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。
--
-- spec 032（H-032-2）：
--   ① 批次表增 channel_code / import_id，唯一约束 uk(period) → uk(channel_code, period, import_id)
--     （CLOSED 批次允许「同周期新导入批次」并存，更正账单可重对；import_id NULL = 032 前历史批次）；
--   ② 三新表：statement_imports（导入批次）+ statement_lines（标准化账单行）+
--     reconciliation_differences（差异拆表，differences_json 停写不停读）；
--   ③ 匹配/一致记录仍以 JSON 内嵌（matches_json），差异为可索引台账。

CREATE DATABASE IF NOT EXISTS `reconciliation` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `reconciliation`;

CREATE TABLE IF NOT EXISTS reconciliation_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(32) NOT NULL COMMENT '业务单号 RB+雪花（ADR-0062）',
    period VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    channel_code VARCHAR(16) NOT NULL DEFAULT 'MOCK' COMMENT '对账渠道（spec 032 §9 ④；历史批次缺省 MOCK）',
    import_id BIGINT NULL COMMENT '账单导入批次 id（spec 032；NULL = 032 前历史批次）',
    status VARCHAR(32) NOT NULL,
    matches_json TEXT,
    differences_json TEXT COMMENT 'spec 032 停写不停读：新批次差异只写 reconciliation_differences',
    closed_at DATETIME NULL,
    closed_by VARCHAR(64) NULL,
    statement_source VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_batches_batch_no (batch_no),
    UNIQUE KEY uk_reconciliation_batches_identity (channel_code, period, import_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 账单导入批次（spec 032 §6.2/§7.1）：一次导入 = 一个渠道 + 一个周期 + 一份内容指纹；
-- RECEIVED → NORMALIZED / REJECTED（REJECTED 不可逆，更正 = 重新导入新批次）。
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

-- 标准化账单行（spec 032 §6.2）：原始行留档（raw_text）以便重解析与争议取证。
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

-- 对账差异台账（spec 032 §8.3 三层幂等之差异层）：uk_diff_identity 吸收同周期同类差异重复产生。
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
