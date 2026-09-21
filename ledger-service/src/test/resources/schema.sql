-- H2 测试 schema：与 deployment/schema/09-ledger-schema.sql（031 目标形态）对齐。
DROP TABLE IF EXISTS account_balances;
DROP TABLE IF EXISTS ledger_periods;
DROP TABLE IF EXISTS ledger_entries;
DROP TABLE IF EXISTS postings;
DROP TABLE IF EXISTS accounts;
DROP TABLE IF EXISTS account_definitions;

CREATE TABLE account_definitions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    normal_balance VARCHAR(8) NOT NULL,
    owner_dimension VARCHAR(16) NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_def_code UNIQUE (code)
);

CREATE TABLE accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    definition_code VARCHAR(32) NOT NULL,
    owner_type VARCHAR(16) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_instance UNIQUE (definition_code, owner_type, owner_id, currency)
);

CREATE TABLE postings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    posting_no VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    period CHAR(7) NOT NULL,
    posted_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_postings_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uk_event_source UNIQUE (event_type, source_id),
    CONSTRAINT uk_postings_posting_no UNIQUE (posting_no)
);

CREATE TABLE ledger_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    posting_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    entry_type VARCHAR(32),
    source_type VARCHAR(16),
    source_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE account_balances (
    account_instance_id BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    debit_total BIGINT NOT NULL DEFAULT 0,
    credit_total BIGINT NOT NULL DEFAULT 0,
    entry_count BIGINT NOT NULL DEFAULT 0,
    last_entry_id BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (account_instance_id, currency)
);

CREATE TABLE ledger_periods (
    period CHAR(7) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'OPEN',
    closed_at TIMESTAMP,
    closed_by VARCHAR(64),
    PRIMARY KEY (period, currency)
);

-- seed（与 09 一致的 id 锚定：历史分录引用不悬空）
INSERT INTO account_definitions
    (id, code, name, type, normal_balance, owner_dimension, status, created_at) VALUES
    (1, 'CUSTOMER_CASH',        '客户资金（历史科目）', 'ASSET',     'DEBIT',  'PLATFORM', 'LEGACY', CURRENT_TIMESTAMP),
    (2, 'MERCHANT_PAYABLE',     '应付商户',             'LIABILITY', 'CREDIT', 'MERCHANT', 'ACTIVE', CURRENT_TIMESTAMP),
    (3, 'FEE_REVENUE',          '平台手续费收入',       'REVENUE',   'CREDIT', 'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP),
    (4, 'SETTLEMENT_PAYABLE',   '已结算待出款',         'LIABILITY', 'CREDIT', 'MERCHANT', 'ACTIVE', CURRENT_TIMESTAMP),
    (5, 'SUSPENSE',             '待处理差错款',         'ASSET',     'DEBIT',  'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP),
    (6, 'CHANNEL_RECEIVABLE',   '渠道应收',             'ASSET',     'DEBIT',  'CHANNEL',  'ACTIVE', CURRENT_TIMESTAMP),
    (7, 'BANK_CASH',            '平台银行现金',         'ASSET',     'DEBIT',  'PLATFORM', 'ACTIVE', CURRENT_TIMESTAMP),
    (8, 'CHANNEL_FEE_EXPENSE',  '渠道手续费成本',       'EXPENSE',   'DEBIT',  'CHANNEL',  'ACTIVE', CURRENT_TIMESTAMP);

INSERT INTO accounts
    (id, definition_code, owner_type, owner_id, currency, code, name, type, created_at) VALUES
    (1,  'CUSTOMER_CASH',       'PLATFORM', 'PLATFORM', 'CNY', 'CUSTOMER_CASH',      '客户资金（历史科目）', 'ASSET',     CURRENT_TIMESTAMP),
    (2,  'MERCHANT_PAYABLE',    'MERCHANT', 'LEGACY',   'CNY', 'MERCHANT_PAYABLE',   '应付商户-历史承接',    'LIABILITY', CURRENT_TIMESTAMP),
    (3,  'FEE_REVENUE',         'PLATFORM', 'PLATFORM', 'CNY', 'FEE_REVENUE',        '平台手续费收入',       'REVENUE',   CURRENT_TIMESTAMP),
    (4,  'SETTLEMENT_PAYABLE',  'MERCHANT', 'LEGACY',   'CNY', 'SETTLEMENT_PAYABLE', '已结算待出款-历史承接','LIABILITY', CURRENT_TIMESTAMP),
    (5,  'SUSPENSE',            'PLATFORM', 'PLATFORM', 'CNY', 'SUSPENSE',           '待处理差错款',         'ASSET',     CURRENT_TIMESTAMP),
    (6,  'BANK_CASH',           'PLATFORM', 'PLATFORM', 'CNY', 'BANK_CASH',          '平台银行现金',         'ASSET',     CURRENT_TIMESTAMP),
    (7,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'ALIPAY',   'CNY', 'CHANNEL_RECEIVABLE', '支付宝渠道应收',       'ASSET',     CURRENT_TIMESTAMP),
    (8,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'WECHAT',   'CNY', 'CHANNEL_RECEIVABLE', '微信渠道应收',         'ASSET',     CURRENT_TIMESTAMP),
    (9,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'DOUYIN',   'CNY', 'CHANNEL_RECEIVABLE', '抖音渠道应收',         'ASSET',     CURRENT_TIMESTAMP),
    (10, 'CHANNEL_RECEIVABLE',  'CHANNEL',  'MOCK',     'CNY', 'CHANNEL_RECEIVABLE', 'MOCK 渠道应收',        'ASSET',     CURRENT_TIMESTAMP),
    (11, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'ALIPAY',   'CNY', 'CHANNEL_FEE_EXPENSE','支付宝渠道手续费成本', 'EXPENSE',   CURRENT_TIMESTAMP),
    (12, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'WECHAT',   'CNY', 'CHANNEL_FEE_EXPENSE','微信渠道手续费成本',   'EXPENSE',   CURRENT_TIMESTAMP),
    (13, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'DOUYIN',   'CNY', 'CHANNEL_FEE_EXPENSE','抖音渠道手续费成本',   'EXPENSE',   CURRENT_TIMESTAMP),
    (14, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'MOCK',     'CNY', 'CHANNEL_FEE_EXPENSE','MOCK 渠道手续费成本',  'EXPENSE',   CURRENT_TIMESTAMP);
