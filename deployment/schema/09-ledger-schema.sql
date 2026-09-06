-- 账本服务自有 Schema（Database-per-Service）：accounts / postings / ledger_entries。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `ledger` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ledger`;

-- 科目：MVP 预置固定科目表（Chart of Accounts），ID 与代码由应用侧 Account 枚举对齐。
CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_accounts_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 记账批次（聚合根）：一次业务事件对应一组平衡分录。
CREATE TABLE IF NOT EXISTS postings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    posting_no VARCHAR(32) NOT NULL COMMENT '业务单号 LP+雪花（ADR-0062）',
    idempotency_key VARCHAR(128) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    -- 幂等兜底：重复记账撞唯一约束后回查返回首次结果（FR-004）
    UNIQUE KEY uk_postings_idempotency_key (idempotency_key),
    UNIQUE KEY uk_postings_posting_no (posting_no),
    KEY idx_postings_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 分录（不可变 append-only）：更正只能新增反向分录。
CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    posting_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_entries_posting (posting_id),
    KEY idx_entries_source (source_type, source_id),
    KEY idx_entries_account (account_id, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- MVP 预置科目（与 com.payment.ledger.domain.Account 枚举一一对应）
INSERT INTO accounts (id, code, name, type, currency, created_at) VALUES
    (1, 'CUSTOMER_CASH',          '客户资金',     'ASSET',     'CNY', NOW()),
    (2, 'MERCHANT_PAYABLE',       '应付商户净额', 'LIABILITY', 'CNY', NOW()),
    (3, 'PLATFORM_FEE_REVENUE',   '平台手续费收入', 'REVENUE',   'CNY', NOW()),
    (4, 'SETTLEMENT_PAYABLE',     '已结算待出款', 'LIABILITY', 'CNY', NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name);
