-- 账本服务自有 Schema（Database-per-Service）：account_definitions / accounts(实例) /
-- postings(LedgerTransaction) / ledger_entries / account_balances(投影) / ledger_periods。
--
-- 031（spec §5 / §9 / §10 / §11 / §14）两级账户模型：Definition 是类型目录（seed 受版本
-- 控制，新增科目 MUST 走 ADR）；可记账的是 Instance（uk_instance 四元组）。
-- 存量库演进脚本：031-ledger-accounting-foundation.sql（information_schema 守卫、可重放）。

CREATE DATABASE IF NOT EXISTS `ledger` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ledger`;

-- ① 科目定义（spec §5.1 / §14①）：normal_balance 由 type 派生落列（ASSET/EXPENSE=DEBIT）。
CREATE TABLE IF NOT EXISTS account_definitions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL COMMENT '科目码（契约枚举 AccountCode，ArchUnit 收口）',
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL COMMENT 'ASSET/LIABILITY/REVENUE/EXPENSE/EQUITY',
    normal_balance VARCHAR(8) NOT NULL COMMENT 'DEBIT/CREDIT（规则方向推导锚）',
    owner_dimension VARCHAR(16) NOT NULL COMMENT 'PLATFORM/CHANNEL/MERCHANT',
    status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' COMMENT 'LEGACY 科目禁止新事件引用',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_def_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ② 账户实例（spec §5.2）：accounts 表就地演进，历史 account_id 引用不悬空；
--    code/name/type 为历史冗余列（与 definition 同步写，读取一律走 definition_code）。
CREATE TABLE IF NOT EXISTS accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    definition_code VARCHAR(32) NOT NULL,
    owner_type VARCHAR(16) NOT NULL COMMENT 'PLATFORM/CHANNEL/MERCHANT',
    owner_id VARCHAR(64) NOT NULL COMMENT 'PLATFORM 单例=PLATFORM；哨兵=LEGACY；否则渠道码/商户号',
    currency VARCHAR(8) NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_instance (definition_code, owner_type, owner_id, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 存量卷就地升级：031 之前建的 accounts 是「科目老表」形态（id/code/name/type/currency/created_at），
-- 没有 definition_code / owner_type / owner_id 三列。CREATE TABLE IF NOT EXISTS 不会改造已存在的表，
-- 于是下方 seed INSERT 直接 ERROR 1054 Unknown column 'definition_code' ⇒ 整个 reset.sh 中断，
-- 后续 10-audit-schema.sql、种子数据全部没跑（2026-09-25 实测复现）。
-- 这里用 information_schema 守卫补列，全新库是 no-op，与 032/034 的可重放写法同款。
SET @sql = IF (
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'definition_code') = 0,
    'ALTER TABLE accounts ADD COLUMN definition_code VARCHAR(32) NULL AFTER id',
    'SELECT ''accounts.definition_code 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF (
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'owner_type') = 0,
    'ALTER TABLE accounts ADD COLUMN owner_type VARCHAR(16) NULL COMMENT ''PLATFORM/CHANNEL/MERCHANT'' AFTER definition_code',
    'SELECT ''accounts.owner_type 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF (
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'owner_id') = 0,
    'ALTER TABLE accounts ADD COLUMN owner_id VARCHAR(64) NULL COMMENT ''PLATFORM 单例=PLATFORM；哨兵=LEGACY；否则渠道码/商户号'' AFTER owner_type',
    'SELECT ''accounts.owner_id 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ 账本交易（LedgerTransaction，spec §9）：一个 Accounting Event 一组平衡分录。
CREATE TABLE IF NOT EXISTS postings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    posting_no VARCHAR(32) NOT NULL COMMENT '业务单号 LP+雪花（ADR-0062）',
    event_type VARCHAR(32) NOT NULL COMMENT '事件语义（AccountingEventType，取代分录 entry_type）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '031 起由 Ledger 派生 {eventType}:{sourceId}（原则 10）',
    source_type VARCHAR(16) NOT NULL COMMENT '来源域 PAYMENT/REFUND/SETTLEMENT/RECONCILIATION',
    source_id VARCHAR(64) NOT NULL COMMENT '业务单号（ADR-0063，禁数值 ID）',
    status VARCHAR(16) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    period CHAR(7) NOT NULL COMMENT '会计期间 YYYY-MM（G2，落库按 posted_at 派生）',
    posted_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    -- 双唯一约束（原则 10）：派生键 + 事件级（历史双前缀缺陷证明约定必被破坏）
    UNIQUE KEY uk_postings_idempotency_key (idempotency_key),
    UNIQUE KEY uk_event_source (event_type, source_id),
    UNIQUE KEY uk_postings_posting_no (posting_no),
    KEY idx_postings_source (source_type, source_id),
    KEY idx_postings_period (period, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ④ 分录（不可变 append-only）：entry_type/source_type/source_id 停写停读（spec §9，
--    列保留 NULL 兼容历史行；追溯走 posting_id join）。
CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    posting_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL COMMENT '031 起指向账户实例（uk_instance.id）',
    direction VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    entry_type VARCHAR(32) NULL,
    source_type VARCHAR(16) NULL,
    source_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_entries_posting (posting_id),
    KEY idx_entries_source (source_type, source_id),
    KEY idx_entries_account (account_id, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ⑤ 余额投影（spec §10 / ADR-0079 方案②）：与交易、分录同一本地事务原子累加；可重建。
CREATE TABLE IF NOT EXISTS account_balances (
    account_instance_id BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    debit_total BIGINT NOT NULL DEFAULT 0,
    credit_total BIGINT NOT NULL DEFAULT 0,
    entry_count BIGINT NOT NULL DEFAULT 0,
    last_entry_id BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (account_instance_id, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ⑥ 会计期间（spec §11）：缺行 = OPEN（保守）。
CREATE TABLE IF NOT EXISTS ledger_periods (
    period CHAR(7) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'OPEN',
    closed_at DATETIME NULL,
    closed_by VARCHAR(64) NULL,
    PRIMARY KEY (period, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ seed：科目定义（id 稳定；与契约枚举 AccountCode 一一对应） ============
INSERT INTO account_definitions
    (id, code, name, type, normal_balance, owner_dimension, status, created_at) VALUES
    (1, 'CUSTOMER_CASH',        '客户资金（历史科目）', 'ASSET',     'DEBIT',  'PLATFORM', 'LEGACY', NOW()),
    (2, 'MERCHANT_PAYABLE',     '应付商户',             'LIABILITY', 'CREDIT', 'MERCHANT', 'ACTIVE', NOW()),
    (3, 'FEE_REVENUE',          '平台手续费收入',       'REVENUE',   'CREDIT', 'PLATFORM', 'ACTIVE', NOW()),
    (4, 'SETTLEMENT_PAYABLE',   '已结算待出款',         'LIABILITY', 'CREDIT', 'MERCHANT', 'ACTIVE', NOW()),
    (5, 'SUSPENSE',             '待处理差错款',         'ASSET',     'DEBIT',  'PLATFORM', 'ACTIVE', NOW()),
    (6, 'CHANNEL_RECEIVABLE',   '渠道应收',             'ASSET',     'DEBIT',  'CHANNEL',  'ACTIVE', NOW()),
    (7, 'BANK_CASH',            '平台银行现金',         'ASSET',     'DEBIT',  'PLATFORM', 'ACTIVE', NOW()),
    (8, 'CHANNEL_FEE_EXPENSE',  '渠道手续费成本',       'EXPENSE',   'DEBIT',  'CHANNEL',  'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name), type = VALUES(type),
    normal_balance = VALUES(normal_balance), owner_dimension = VALUES(owner_dimension),
    status = VALUES(status);

-- ============ seed：账户实例 ============
-- 历史 id 1~5 就地承接（spec §5.3：不留两套模型，历史分录按 id 引用不悬空；
-- id=3 PLATFORM_FEE_REVENUE 就地更名 FEE_REVENUE）；2 号行为 LEGACY 哨兵（偏差登记：
-- owner_type=MERCHANT 而非 §5.2 的 PLATFORM 写法，语义等价、商户应付合并查询更直）。
-- 渠道实例（§5.4「渠道注册表 = seed 行本身」）：ALIPAY/WECHAT/DOUYIN/MOCK。
INSERT INTO accounts
    (id, definition_code, owner_type, owner_id, currency, code, name, type, created_at) VALUES
    (1,  'CUSTOMER_CASH',       'PLATFORM', 'PLATFORM', 'CNY', 'CUSTOMER_CASH',      '客户资金（历史科目）', 'ASSET',     NOW()),
    (2,  'MERCHANT_PAYABLE',    'MERCHANT', 'LEGACY',   'CNY', 'MERCHANT_PAYABLE',   '应付商户-历史承接',    'LIABILITY', NOW()),
    (3,  'FEE_REVENUE',         'PLATFORM', 'PLATFORM', 'CNY', 'FEE_REVENUE',        '平台手续费收入',       'REVENUE',   NOW()),
    (4,  'SETTLEMENT_PAYABLE',  'MERCHANT', 'LEGACY',   'CNY', 'SETTLEMENT_PAYABLE', '已结算待出款-历史承接','LIABILITY', NOW()),
    (5,  'SUSPENSE',            'PLATFORM', 'PLATFORM', 'CNY', 'SUSPENSE',           '待处理差错款',         'ASSET',     NOW()),
    (6,  'BANK_CASH',           'PLATFORM', 'PLATFORM', 'CNY', 'BANK_CASH',          '平台银行现金',         'ASSET',     NOW()),
    (7,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'ALIPAY',   'CNY', 'CHANNEL_RECEIVABLE', '支付宝渠道应收',       'ASSET',     NOW()),
    (8,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'WECHAT',   'CNY', 'CHANNEL_RECEIVABLE', '微信渠道应收',         'ASSET',     NOW()),
    (9,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'DOUYIN',   'CNY', 'CHANNEL_RECEIVABLE', '抖音渠道应收',         'ASSET',     NOW()),
    (10, 'CHANNEL_RECEIVABLE',  'CHANNEL',  'MOCK',     'CNY', 'CHANNEL_RECEIVABLE', 'MOCK 渠道应收',        'ASSET',     NOW()),
    (11, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'ALIPAY',   'CNY', 'CHANNEL_FEE_EXPENSE','支付宝渠道手续费成本', 'EXPENSE',   NOW()),
    (12, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'WECHAT',   'CNY', 'CHANNEL_FEE_EXPENSE','微信渠道手续费成本',   'EXPENSE',   NOW()),
    (13, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'DOUYIN',   'CNY', 'CHANNEL_FEE_EXPENSE','抖音渠道手续费成本',   'EXPENSE',   NOW()),
    (14, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'MOCK',     'CNY', 'CHANNEL_FEE_EXPENSE','MOCK 渠道手续费成本',  'EXPENSE',   NOW()),
    -- 渠道插件化：STRIPE 渠道实例（新增渠道 MUST 同步开户，与 031 保持一致）
    (15, 'CHANNEL_RECEIVABLE',  'CHANNEL',  'STRIPE',   'CNY', 'CHANNEL_RECEIVABLE', 'Stripe 渠道应收',      'ASSET',     NOW()),
    (16, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'STRIPE',   'CNY', 'CHANNEL_FEE_EXPENSE','Stripe 渠道手续费成本','EXPENSE',   NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name), code = VALUES(code), type = VALUES(type);
