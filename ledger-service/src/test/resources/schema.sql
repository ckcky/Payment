DROP TABLE IF EXISTS ledger_entries;
DROP TABLE IF EXISTS postings;
DROP TABLE IF EXISTS accounts;

CREATE TABLE accounts (
    id BIGINT NOT NULL PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_accounts_code UNIQUE (code)
);

CREATE TABLE postings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_postings_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE ledger_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    posting_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

INSERT INTO accounts (id, code, name, type, currency, created_at) VALUES
    (1, 'CUSTOMER_CASH', '客户资金', 'ASSET', 'CNY', CURRENT_TIMESTAMP),
    (2, 'MERCHANT_PAYABLE', '应付商户净额', 'LIABILITY', 'CNY', CURRENT_TIMESTAMP),
    (3, 'PLATFORM_FEE_REVENUE', '平台手续费收入', 'REVENUE', 'CNY', CURRENT_TIMESTAMP),
    (4, 'SETTLEMENT_PAYABLE', '已结算待出款', 'LIABILITY', 'CNY', CURRENT_TIMESTAMP);
