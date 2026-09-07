-- =============================================================================
-- Feature 019 迁移：order 驱动两层退款单（TXRF/PMRF，ADR-0067）
--
-- 作用：
--   ① order 库    transaction_refunds 新表（交易层退款单，TXRF+雪花）；
--                 transactions 加 payment_no（生效支付单）/ refunded_minor（累计已退），
--                 payment_no 存量回填自 orders.payment_no
--   ② payment 库  refunds 加 transaction_refund_no（上层 TXRF，普通索引；
--                 spec 019 起幂等键载体由商户单号变为上层单号）
--
-- 幂等可重放：沿用 015/018 模式（information_schema 守卫 + PREPARE 动态 SQL）。
-- 禁用 `ADD COLUMN IF NOT EXISTS` / `ADD INDEX IF NOT EXISTS`（MariaDB 方言，MySQL 8 报错——016 教训）。
-- 列序守 018 规范（ADR-0066）：id → 业务主键 → 唯一索引列 → 业务列 → 审计列殿后。
-- =============================================================================

-- ① USE `order` ---------------------------------------------------------------
USE `order`;

-- 1.1 交易层退款单新表（CREATE TABLE IF NOT EXISTS 为标准 MySQL 语法，可直接幂等）
CREATE TABLE IF NOT EXISTS transaction_refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_no VARCHAR(32) NOT NULL COMMENT '交易层退款单号 TXRF+雪花（ADR-0062/0067）',
    payment_refund_no VARCHAR(32) NULL COMMENT '支付层退款执行单号 PMRF+雪花（payment 响应回填，ADR-0067）',
    transaction_no VARCHAR(32) NOT NULL COMMENT '所属交易（TX+雪花，ADR-0062）',
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（OR+雪花，ADR-0063）',
    payment_no VARCHAR(32) NOT NULL COMMENT '被退支付单（PM+雪花，ADR-0063）',
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'REQUESTED/PROCESSING/SUCCEEDED/FAILED/REJECTED',
    reason VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键=TXRF（同号重试可重入回放）',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_refunds_refund_no (refund_no),
    UNIQUE KEY uk_transaction_refunds_idempotency_key (idempotency_key),
    KEY idx_transaction_refunds_transaction_no (transaction_no),
    KEY idx_transaction_refunds_payment_refund_no (payment_refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1.2 transactions 加 payment_no（生效支付单，status 之后）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions' AND COLUMN_NAME = 'payment_no') = 0,
  'ALTER TABLE transactions ADD COLUMN payment_no VARCHAR(32) NULL COMMENT ''生效支付单：首张成功支付（spec 019 / ADR-0067；surplus 被退单不覆盖）'' AFTER status',
  'SELECT ''transactions.payment_no 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.3 transactions 加 refunded_minor（累计已退，payment_no 之后）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'transactions' AND COLUMN_NAME = 'refunded_minor') = 0,
  'ALTER TABLE transactions ADD COLUMN refunded_minor BIGINT NOT NULL DEFAULT 0 COMMENT ''累计已退金额（spec 019 / ADR-0067）'' AFTER payment_no',
  'SELECT ''transactions.refunded_minor 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.4 存量回填：payment_no 自 orders.payment_no 同步（生效支付单单一事实源）
UPDATE transactions t
JOIN orders o ON o.order_no = t.order_no
SET t.payment_no = o.payment_no
WHERE t.payment_no IS NULL AND o.payment_no IS NOT NULL;

-- ② USE `payment` -------------------------------------------------------------
USE `payment`;

-- 2.1 refunds 加 transaction_refund_no（refund_no 之后）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND COLUMN_NAME = 'transaction_refund_no') = 0,
  'ALTER TABLE refunds ADD COLUMN transaction_refund_no VARCHAR(32) NULL COMMENT ''上层交易退款单 TXRF（spec 019 / ADR-0067；幂等键载体）'' AFTER refund_no',
  'SELECT ''refunds.transaction_refund_no 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.2 refunds 加 transaction_no（所属交易 TX，transaction_refund_no 之后；T108 补充：
--     退款回调通知 order 时按 RefundResultNotification 契约回传）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND COLUMN_NAME = 'transaction_no') = 0,
  'ALTER TABLE refunds ADD COLUMN transaction_no VARCHAR(32) NULL COMMENT ''所属交易单 TX（spec 019 / ADR-0067；回调通知 order 时回传）'' AFTER transaction_refund_no',
  'SELECT ''refunds.transaction_no 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.3 refunds 加普通索引 idx_refunds_transaction_refund_no
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refunds' AND INDEX_NAME = 'idx_refunds_transaction_refund_no') = 0,
  'ALTER TABLE refunds ADD INDEX idx_refunds_transaction_refund_no (transaction_refund_no)',
  'SELECT ''idx_refunds_transaction_refund_no 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
