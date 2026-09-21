-- 对账服务自有 Schema（Database-per-Service）：reconciliation_batches。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。
-- 匹配/差异以 JSON 内嵌于批次（matches_json / differences_json），避免对账结果拆表带来跨表一致性成本。

CREATE DATABASE IF NOT EXISTS `reconciliation` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `reconciliation`;

CREATE TABLE IF NOT EXISTS reconciliation_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(32) NOT NULL COMMENT '业务单号 RB+雪花（ADR-0062）',
    period VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    matches_json TEXT,
    differences_json TEXT,
    closed_at DATETIME NULL,
    closed_by VARCHAR(64) NULL,
    statement_source VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_batches_period (period),
    UNIQUE KEY uk_reconciliation_batches_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- spec 031 §12 / 034 §9：出站失败台账（reconciliation 调用方侧；AUDIT 调整事件）。
-- 表结构与 034-pending-postings.sql / 03-payment-schema.sql 逐字一致（双路径重放快照一致性）。
CREATE TABLE IF NOT EXISTS pending_postings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型：ADJUSTMENT',
    source_type VARCHAR(32) NOT NULL COMMENT '来源域：RECONCILIATION',
    source_id VARCHAR(64) NOT NULL COMMENT '来源业务单号（ADR-0063）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '记账事件派生键 {eventType}:{sourceId}（031 原则 10）',
    payload_json TEXT NOT NULL COMMENT '重放载荷（原样重发，不重算派生键）',
    fail_reason VARCHAR(512) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REPOSTED/ABANDONED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_postings_event_source (event_type, source_id),
    KEY idx_pending_postings_status (status, retry_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
