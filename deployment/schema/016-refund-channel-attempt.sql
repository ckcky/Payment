-- =============================================================================
-- Feature 016 迁移：payment_attempts 复用为渠道交互记录表（ADR-0054 / FR-017，N4 修复）
--
-- 作用：
--   payment_attempts 增加 attempt_type 列区分支付/退款尝试（存量行默认 PAYMENT）；
--   退款尝试落同一表（payment_no 关联 + channel_reference = 渠道退款流水号，唯一约束兜底），
--   修复「退款渠道流水号被丢弃」对账缺口（N4）。
--
-- 幂等可重放（033 修正：本文件曾是全仓唯一 `ADD COLUMN IF NOT EXISTS`（MariaDB 方言）残留，
--   在 MySQL 8 上语法错，导致「存量库升级」路径直接失败——spec 033 §6.4 L-1 的正主；
--   现改写为 015/018/019/030/031 确立的守卫模式：information_schema 守卫 + PREPARE 动态 SQL）。
--
-- 存量行为：存量行 attempt_type 由列 DEFAULT 'PAYMENT' 兜底，不做额外回填；
--   全新库无需执行本脚本（03-payment-schema.sql 已是目标形态，本脚本整体 no-op）。
-- =============================================================================

USE `payment`;

-- 1) attempt_type 列（不存在才新增；存在即 no-op）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'attempt_type') = 0,
  'ALTER TABLE payment_attempts ADD COLUMN attempt_type VARCHAR(16) NOT NULL DEFAULT ''PAYMENT''',
  'SELECT ''payment_attempts.attempt_type 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 退款事实按 (payment_no, attempt_type) 定位退款渠道尝试的索引（不存在才新增）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND INDEX_NAME = 'idx_attempts_payment_type') = 0,
  'ALTER TABLE payment_attempts ADD INDEX idx_attempts_payment_type (payment_no, attempt_type)',
  'SELECT ''idx_attempts_payment_type 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
