-- 支付服务自有 Schema（Database-per-Service）：payments / payment_attempts。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `payment` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `payment`;

CREATE TABLE IF NOT EXISTS payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
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
    UNIQUE KEY uk_payments_transaction_id (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_id BIGINT NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    requested_at DATETIME NOT NULL,
    responded_at DATETIME NULL,
    channel_reference VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    retry_count INT NOT NULL DEFAULT 0,
    error_type VARCHAR(16) NULL,
    next_retry_at DATETIME NULL,
    -- 重试调度扫描：next_retry_at 到期且非空的尝试（spec US3）
    KEY idx_attempts_next_retry_at (next_retry_at),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_attempts_payment_id (payment_id),
    UNIQUE KEY uk_attempts_channel_reference (channel_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
