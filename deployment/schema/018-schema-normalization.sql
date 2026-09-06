-- =============================================================================
-- Feature 018 迁移：表结构列序规范化 + payment_attempts 金额留痕 + 按 order_item 粒度履约
-- ADR-0066 / spec 018（FR-001~006）
--
-- 作用：
--   ① order 库      order_items 加 order_item_no（OI+雪花，第 2 列）+ 唯一键
--   ② payment 库    payment_attempts 加 amount_minor/currency_code（第 5、6 列）并回填；
--                   payments / refunds / payment_attempts 列序规范化
--   ③ fulfillment 库 fulfillments 存量 NULL 回填 LEGACY-{id}，order_item_id 收紧 NOT NULL，
--                   唯一键 uk_fulfillments_source_payment_no → (source_payment_no, order_item_id)
--   ④ entitlement 库 entitlements 列序（source_fulfillment_id 提第 2 位）
--   ⑤ settlement 库  settlement_batches 列序（idempotency_key 提至 period 之后）
--
-- 幂等可重放：沿用 015 模式（information_schema 守卫 + PREPARE 动态 SQL）。
-- 禁用 `ADD COLUMN IF NOT EXISTS`（MariaDB 方言，MySQL 8 报错——016 教训）。
-- 注意：MODIFY COLUMN ... AFTER 为 COPY 重建，demo/开发数据量级无压力；
--       生产执行请放低峰窗口。
-- 列序规范（ADR-0066 长效约束）：第 1 列自增 id → 第 2 列业务主键 → 唯一索引列 →
--       其余业务列（相对顺序稳定）→ 审计列殿后。特例豁免：refund_intake_locks /
--       accounts / stock_reservation。
-- =============================================================================

-- ① USE `order` ---------------------------------------------------------------
USE `order`;
SET @db = DATABASE();

-- 1.1 order_items 加列（先 NULL 便于回填；第 2 位）
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'order_items' AND COLUMN_NAME = 'order_item_no');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE order_items ADD COLUMN order_item_no VARCHAR(32) NULL AFTER id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.2 回填：'OI'+自增 id 短数字，不与 19 位雪花撞号（幂等：只补 NULL）
UPDATE order_items SET order_item_no = CONCAT('OI', id) WHERE order_item_no IS NULL;

-- 1.3 收紧 NOT NULL + 唯一键（MODIFY 无 AFTER 保持位置；唯一键守卫）
SET @sql = 'ALTER TABLE order_items MODIFY COLUMN order_item_no VARCHAR(32) NOT NULL AFTER id';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'order_items' AND INDEX_NAME = 'uk_order_items_order_item_no');
SET @sql = IF(@has_idx = 0,
    'ALTER TABLE order_items ADD UNIQUE KEY uk_order_items_order_item_no (order_item_no)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② USE `payment` -------------------------------------------------------------
USE `payment`;
SET @db = DATABASE();

-- 2.1 payment_attempts 加金额列（第 5、6 位，attempt_type 之后）
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'amount_minor');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE payment_attempts ADD COLUMN amount_minor BIGINT NULL AFTER attempt_type',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'currency_code');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE payment_attempts ADD COLUMN currency_code VARCHAR(8) NULL AFTER amount_minor',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.2 回填：按所属支付单取金额/币种（REFUND 尝试记所属支付单金额，口径见 FR-002）
UPDATE payment_attempts a JOIN payments p ON a.payment_no = p.payment_no
  SET a.amount_minor = p.amount_minor, a.currency_code = p.currency_code
  WHERE a.amount_minor IS NULL;

-- 2.3 收紧 NOT NULL；并把 attempt_type 归位第 4 列（存量库 016 时代 ADD COLUMN 追加在表尾，
--     必须先归位 attempt_type，amount/currency 的 AFTER 链才能落到 FR-001 目标位）；
--     MODIFY ... AFTER 幂等可重放
SET @sql = 'ALTER TABLE payment_attempts
  MODIFY COLUMN attempt_type VARCHAR(16) NOT NULL DEFAULT ''PAYMENT'' COMMENT ''尝试类型 PAYMENT/REFUND（Feature 016 / FR-017）'' AFTER channel_code,
  MODIFY COLUMN amount_minor BIGINT NOT NULL COMMENT ''资金口径：PAYMENT=支付金额；REFUND=所属支付单金额（spec 018 / FR-002）'' AFTER attempt_type,
  MODIFY COLUMN currency_code VARCHAR(8) NOT NULL AFTER amount_minor';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.4 payment_attempts 列序：requested_at/responded_at/version 挪最后三列（FR-001）
SET @sql = 'ALTER TABLE payment_attempts
  MODIFY COLUMN requested_at DATETIME NOT NULL AFTER updated_by,
  MODIFY COLUMN responded_at DATETIME NULL AFTER requested_at,
  MODIFY COLUMN version INT NOT NULL DEFAULT 1 AFTER responded_at';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.5 payments 列序：idempotency_key 提至 payment_no 之后（其余列相对顺序不变）
SET @sql = 'ALTER TABLE payments
  MODIFY COLUMN idempotency_key VARCHAR(128) NOT NULL AFTER payment_no';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.6 refunds 列序：idempotency_key 提至 refund_no 之后
SET @sql = 'ALTER TABLE refunds
  MODIFY COLUMN idempotency_key VARCHAR(128) NOT NULL AFTER refund_no';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ USE `fulfillment` ---------------------------------------------------------
USE `fulfillment`;
SET @db = DATABASE();

-- 3.1 存量 NULL 回填 LEGACY-{id}（存量=整单一条履约，保新 uk 唯一）
UPDATE fulfillments SET order_item_id = CONCAT('LEGACY-', id) WHERE order_item_id IS NULL;

-- 3.2 列序 + 收紧：source_payment_no 提第 2 位，order_item_id NOT NULL 紧随 order_no
SET @sql = 'ALTER TABLE fulfillments
  MODIFY COLUMN source_payment_no VARCHAR(32) NOT NULL COMMENT ''来源支付单（业务单号 PM+雪花，ADR-0063）'' AFTER id,
  MODIFY COLUMN order_item_id VARCHAR(64) NOT NULL COMMENT ''订单明细业务单号 OI+雪花（spec 018 / ADR-0066）'' AFTER order_no';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3.3 唯一键换轨：uk(source_payment_no) → uk(source_payment_no, order_item_id)
SET @has_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'fulfillments' AND INDEX_NAME = 'uk_fulfillments_source_payment_no');
SET @sql = IF(@has_old > 0,
    'ALTER TABLE fulfillments DROP INDEX uk_fulfillments_source_payment_no',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_new = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'fulfillments' AND INDEX_NAME = 'uk_fulfillments_source_payment_item');
SET @sql = IF(@has_new = 0,
    'ALTER TABLE fulfillments ADD UNIQUE KEY uk_fulfillments_source_payment_item (source_payment_no, order_item_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ④ USE `entitlement` ---------------------------------------------------------
USE `entitlement`;
SET @db = DATABASE();

-- 4.1 entitlements 列序：uk 列 source_fulfillment_id 提第 2 位
SET @sql = 'ALTER TABLE entitlements
  MODIFY COLUMN source_fulfillment_id VARCHAR(64) NOT NULL AFTER id';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ⑤ USE `settlement` ----------------------------------------------------------
USE `settlement`;
SET @db = DATABASE();

-- 5.1 settlement_batches 列序：idempotency_key 提至 batch_no/merchant_id/period 之后
SET @sql = 'ALTER TABLE settlement_batches
  MODIFY COLUMN idempotency_key VARCHAR(128) NOT NULL AFTER period';
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
