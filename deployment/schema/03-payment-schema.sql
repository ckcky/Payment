-- 支付服务自有 Schema（Database-per-Service）：payments / payment_attempts。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `payment` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `payment`;

CREATE TABLE IF NOT EXISTS payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(32) NOT NULL COMMENT '业务单号 PM+雪花（ADR-0062）',
    transaction_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    attempt_seq INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    current_attempt_id BIGINT,
    failure_reason VARCHAR(255),
    query_attempts INT NOT NULL DEFAULT 0,
    entered_unknown_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_idempotency_key (idempotency_key),
    KEY idx_payments_transaction_id (transaction_id),
    KEY idx_payments_txn_seq (transaction_id, attempt_seq),
    UNIQUE KEY uk_payments_payment_no (payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(32) NOT NULL COMMENT '所属支付单（业务单号 PM+雪花，ADR-0063）',
    channel_code VARCHAR(32) NOT NULL,
    requested_at DATETIME NOT NULL,
    responded_at DATETIME NULL,
    channel_reference VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    retry_count INT NOT NULL DEFAULT 0,
    -- 错误分类（由双响应码派生，仅观测用；重试判定不读它，ADR-0012/0013）
    error_type VARCHAR(16) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_attempts_payment_no (payment_no),
    UNIQUE KEY uk_attempts_channel_reference (channel_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
