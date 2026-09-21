-- =============================================================================
-- Feature 031 迁移：Ledger / Accounting 地基（spec 031 §14 变更集）
--
-- 作用（存量库就地演进，不留两套模型）：
--   ① account_definitions / account_balances / ledger_periods 三新表 + seed（幂等重放）；
--   ② accounts 就地升级为「账户实例表」：definition_code/owner_type/owner_id + uk_instance，
--      历史 id=1..5 承接（id=3 就地更名 FEE_REVENUE；id=2/4 落 LEGACY 哨兵），
--      渠道/平台新实例 seed；id 改 AUTO_INCREMENT（新实例自动开户需要）；
--   ③ postings 升级为 LedgerTransaction：event_type/period/posted_at + uk_event_source
--      （历史行回填：event_type 按来源域映射、posted_at=created_at、period 由 created_at 派生）；
--   ④ ledger_entries 的 entry_type/source_type/source_id 改可空（停写停读，历史行保留）。
--
-- 幂等可重放：沿用 015/018/019/030 模式（information_schema 守卫 + PREPARE 动态 SQL）。
-- 禁用 `ADD COLUMN IF NOT EXISTS`（MariaDB 方言，MySQL 8 报错——016 教训）。
-- 全新库无需执行本脚本（09-ledger-schema.sql 已是目标形态；reset.sh 全量重放时本脚本
-- 各项守卫条件均为假，整体为无害 no-op）。
--
-- 历史行 event_type 回填映射（§5.3 / §6.1）：
--   PAYMENT→PAYMENT_CAPTURE、REFUND→REFUND、SETTLEMENT→MERCHANT_SETTLEMENT、
--   ADJUSTMENT→ADJUSTMENT。source_id 数值 batchId 的存量 SETTLEMENT 行**不迁移**
--   （口径同 ADR-0065「存量数据不处理」）；新流水起用 MERCHANT_SETTLEMENT:batchNo。
-- =============================================================================

USE `ledger`;

-- ---------------------------------------------------------------------------
-- ① 三新表（DDL 与 09-ledger-schema.sql 完全一致，IF NOT EXISTS 可重放）
-- ---------------------------------------------------------------------------
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

CREATE TABLE IF NOT EXISTS ledger_periods (
    period CHAR(7) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'OPEN',
    closed_at DATETIME NULL,
    closed_by VARCHAR(64) NULL,
    PRIMARY KEY (period, currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- seed：科目定义（与 09 一致，ON DUPLICATE 覆盖语义字段，重放安全）
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

-- ---------------------------------------------------------------------------
-- ② accounts → 账户实例表（先加可空列 → 回填 → 收紧 NOT NULL → uk_instance）
-- ---------------------------------------------------------------------------
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'definition_code') = 0,
  'ALTER TABLE accounts ADD COLUMN definition_code VARCHAR(32) NULL AFTER id',
  'SELECT ''accounts.definition_code 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'owner_type') = 0,
  'ALTER TABLE accounts ADD COLUMN owner_type VARCHAR(16) NULL AFTER definition_code',
  'SELECT ''accounts.owner_type 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'owner_id') = 0,
  'ALTER TABLE accounts ADD COLUMN owner_id VARCHAR(64) NULL AFTER owner_type',
  'SELECT ''accounts.owner_id 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填存量 5 行（spec §5.3 迁移表；id=3 就地更名 FEE_REVENUE，id 保留、历史分录不悬空）
UPDATE accounts SET
    definition_code = CASE id
        WHEN 1 THEN 'CUSTOMER_CASH' WHEN 2 THEN 'MERCHANT_PAYABLE' WHEN 3 THEN 'FEE_REVENUE'
        WHEN 4 THEN 'SETTLEMENT_PAYABLE' WHEN 5 THEN 'SUSPENSE' END,
    owner_type = CASE WHEN id IN (2, 4) THEN 'MERCHANT' ELSE 'PLATFORM' END,
    owner_id   = CASE WHEN id IN (2, 4) THEN 'LEGACY' ELSE 'PLATFORM' END
WHERE definition_code IS NULL AND id BETWEEN 1 AND 5;

UPDATE accounts SET code = 'FEE_REVENUE', name = '平台手续费收入' WHERE id = 3 AND code <> 'FEE_REVENUE';

-- 收紧 NOT NULL（仅当当前可空时执行——重放安全）
SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'definition_code') = 'YES',
  'ALTER TABLE accounts MODIFY definition_code VARCHAR(32) NOT NULL',
  'SELECT ''accounts.definition_code 已 NOT NULL'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'owner_type') = 'YES',
  'ALTER TABLE accounts MODIFY owner_type VARCHAR(16) NOT NULL',
  'SELECT ''accounts.owner_type 已 NOT NULL'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'owner_id') = 'YES',
  'ALTER TABLE accounts MODIFY owner_id VARCHAR(64) NOT NULL',
  'SELECT ''accounts.owner_id 已 NOT NULL'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- id 改 AUTO_INCREMENT（自动开户需要；原表为手工指定主键）
SET @sql = IF((SELECT EXTRA FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'id') NOT LIKE '%auto_increment%',
  'ALTER TABLE accounts MODIFY id BIGINT NOT NULL AUTO_INCREMENT',
  'SELECT ''accounts.id 已 AUTO_INCREMENT'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧唯一键 code 与新模型冲突（同 definition 多渠道实例）——存在即删
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND INDEX_NAME = 'uk_accounts_code') > 0,
  'ALTER TABLE accounts DROP INDEX uk_accounts_code',
  'SELECT ''uk_accounts_code 已删除'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'accounts' AND INDEX_NAME = 'uk_instance') = 0,
  'ALTER TABLE accounts ADD UNIQUE KEY uk_instance (definition_code, owner_type, owner_id, currency)',
  'SELECT ''uk_instance 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- seed：平台/渠道实例（与 09 一致；商户实例按 §5.4 由账本自动开立，不预置）
INSERT INTO accounts
    (id, definition_code, owner_type, owner_id, currency, code, name, type, created_at) VALUES
    (6,  'BANK_CASH',           'PLATFORM', 'PLATFORM', 'CNY', 'BANK_CASH',          '平台银行现金',         'ASSET',     NOW()),
    (7,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'ALIPAY',   'CNY', 'CHANNEL_RECEIVABLE', '支付宝渠道应收',       'ASSET',     NOW()),
    (8,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'WECHAT',   'CNY', 'CHANNEL_RECEIVABLE', '微信渠道应收',         'ASSET',     NOW()),
    (9,  'CHANNEL_RECEIVABLE',  'CHANNEL',  'DOUYIN',   'CNY', 'CHANNEL_RECEIVABLE', '抖音渠道应收',         'ASSET',     NOW()),
    (10, 'CHANNEL_RECEIVABLE',  'CHANNEL',  'MOCK',     'CNY', 'CHANNEL_RECEIVABLE', 'MOCK 渠道应收',        'ASSET',     NOW()),
    (11, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'ALIPAY',   'CNY', 'CHANNEL_FEE_EXPENSE','支付宝渠道手续费成本', 'EXPENSE',   NOW()),
    (12, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'WECHAT',   'CNY', 'CHANNEL_FEE_EXPENSE','微信渠道手续费成本',   'EXPENSE',   NOW()),
    (13, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'DOUYIN',   'CNY', 'CHANNEL_FEE_EXPENSE','抖音渠道手续费成本',   'EXPENSE',   NOW()),
    (14, 'CHANNEL_FEE_EXPENSE', 'CHANNEL',  'MOCK',     'CNY', 'CHANNEL_FEE_EXPENSE','MOCK 渠道手续费成本',  'EXPENSE',   NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name), code = VALUES(code), type = VALUES(type);

-- ---------------------------------------------------------------------------
-- ③ postings → LedgerTransaction
-- ---------------------------------------------------------------------------
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND COLUMN_NAME = 'event_type') = 0,
  'ALTER TABLE postings ADD COLUMN event_type VARCHAR(32) NULL AFTER posting_no',
  'SELECT ''postings.event_type 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND COLUMN_NAME = 'period') = 0,
  'ALTER TABLE postings ADD COLUMN period CHAR(7) NULL AFTER currency',
  'SELECT ''postings.period 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND COLUMN_NAME = 'posted_at') = 0,
  'ALTER TABLE postings ADD COLUMN posted_at DATETIME NULL AFTER period',
  'SELECT ''postings.posted_at 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史行回填（CASE 无 ELSE：出现未登记来源域时留 NULL，由收紧 NOT NULL 失败暴露而非瞎猜）
UPDATE postings SET
    event_type = CASE source_type
        WHEN 'PAYMENT' THEN 'PAYMENT_CAPTURE' WHEN 'REFUND' THEN 'REFUND'
        WHEN 'SETTLEMENT' THEN 'MERCHANT_SETTLEMENT' WHEN 'ADJUSTMENT' THEN 'ADJUSTMENT' END,
    posted_at = COALESCE(posted_at, created_at),
    period = COALESCE(period, DATE_FORMAT(created_at, '%Y-%m'))
WHERE event_type IS NULL;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND COLUMN_NAME = 'event_type') = 'YES',
  'ALTER TABLE postings MODIFY event_type VARCHAR(32) NOT NULL',
  'SELECT ''postings.event_type 已 NOT NULL'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND COLUMN_NAME = 'period') = 'YES',
  'ALTER TABLE postings MODIFY period CHAR(7) NOT NULL',
  'SELECT ''postings.period 已 NOT NULL'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND COLUMN_NAME = 'posted_at') = 'YES',
  'ALTER TABLE postings MODIFY posted_at DATETIME NOT NULL',
  'SELECT ''postings.posted_at 已 NOT NULL'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND INDEX_NAME = 'uk_event_source') = 0,
  'ALTER TABLE postings ADD UNIQUE KEY uk_event_source (event_type, source_id)',
  'SELECT ''uk_event_source 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'postings' AND INDEX_NAME = 'idx_postings_period') = 0,
  'ALTER TABLE postings ADD KEY idx_postings_period (period, status)',
  'SELECT ''idx_postings_period 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- ④ ledger_entries：entry_type / source_type / source_id 停写停读 → 可空
-- ---------------------------------------------------------------------------
SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ledger_entries' AND COLUMN_NAME = 'entry_type') = 'NO',
  'ALTER TABLE ledger_entries MODIFY entry_type VARCHAR(32) NULL',
  'SELECT ''ledger_entries.entry_type 已可空'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ledger_entries' AND COLUMN_NAME = 'source_type') = 'NO',
  'ALTER TABLE ledger_entries MODIFY source_type VARCHAR(16) NULL',
  'SELECT ''ledger_entries.source_type 已可空'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ledger_entries' AND COLUMN_NAME = 'source_id') = 'NO',
  'ALTER TABLE ledger_entries MODIFY source_id VARCHAR(64) NULL',
  'SELECT ''ledger_entries.source_id 已可空'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- ⑤ payment 库：payments 加 merchant_id（spec §13，H11 前置收编：一次加列，032 不再重复动）
--    历史行**不回填**（口径同 030 extra_json）：merchant_id NULL 的行不产生新记账事件；
--    新流水由 order 下单请求携带 merchantId 写入。
-- ---------------------------------------------------------------------------
USE `payment`;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'merchant_id') = 0,
  'ALTER TABLE payments ADD COLUMN merchant_id VARCHAR(64) NULL COMMENT ''商户号（spec 031 / §13，PAYMENT_CAPTURE 事实锚；历史行 NULL）'' AFTER user_id',
  'SELECT ''payments.merchant_id 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
