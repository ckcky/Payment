-- Feature 015 迁移：一交易多支付单。
-- 作用：把旧库中 payments 表的 `uk_payments_transaction_id` 唯一约束降级为普通索引，
--       并新增 `attempt_seq` 列与组合索引，以支持同一交易下多张支付单。
-- 幂等可重放：全新库（deployment/schema/03-payment-schema.sql 已是新版）执行本脚本时
--       各分支走 SELECT 1 空操作，不会报错；已初始化的旧库则执行真实 DDL。
-- 本脚本自带 USE payment（依赖 DATABASE() 定位目标库；直接管道进 mysql 客户端
-- 若不选库，ALTER TABLE 会报 ERROR 1046 No database selected）。
USE `payment`;

SET @db = DATABASE();

-- 1) transaction_id 唯一约束 → 普通索引（存在才降级）
SET @has_uk = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payments'
      AND INDEX_NAME = 'uk_payments_transaction_id');
SET @sql = IF(@has_uk > 0,
    'ALTER TABLE payments DROP INDEX uk_payments_transaction_id, ADD INDEX idx_payments_transaction_id (transaction_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 新增 attempt_seq 列（不存在才新增）
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'attempt_seq');
SET @sql = IF(@has_col = 0,
    'ALTER TABLE payments ADD COLUMN attempt_seq INT NOT NULL DEFAULT 1',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) 新增组合索引 (transaction_id, attempt_seq)（不存在才新增）
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_txn_seq');
SET @sql = IF(@has_idx = 0,
    'ALTER TABLE payments ADD INDEX idx_payments_txn_seq (transaction_id, attempt_seq)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
