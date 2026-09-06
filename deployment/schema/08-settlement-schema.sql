-- 结算服务自有 Schema（Database-per-Service）：settlement_batches / settlement_items。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。
-- 金额字段均为最小货币单位 BIGINT（禁止浮点）；批次以 (merchant_id, period) 与 idempotency_key 双唯一约束兜底并发幂等。

CREATE DATABASE IF NOT EXISTS `settlement` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `settlement`;

CREATE TABLE IF NOT EXISTS settlement_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(32) NOT NULL COMMENT '业务单号 SB+雪花（ADR-0062）',
    merchant_id VARCHAR(32) NOT NULL,
    period VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    income_minor BIGINT NOT NULL,
    refund_minor BIGINT NOT NULL,
    adjustment_minor BIGINT NOT NULL,
    net_minor BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    fact_count INT NOT NULL DEFAULT 0,
    source_period VARCHAR(32),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_batches_batch_no (batch_no),
    UNIQUE KEY uk_settlement_batches_merchant_period (merchant_id, period),
    UNIQUE KEY uk_settlement_batches_idempotency_key (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS settlement_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    reference VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_settlement_items_batch_id (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS settlement_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NOT NULL,
    merchant_id VARCHAR(32) NOT NULL,
    period VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    direction VARCHAR(16) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_adjustments_idem (idempotency_key),
    KEY idx_settlement_adjustments_scope (merchant_id, period, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
