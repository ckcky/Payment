-- =============================================================================
-- Feature 030 迁移：payment 库 payment_attempts 加 extra_json（渠道模态载体）
--
-- 作用：
--   payment 库 payment_attempts 加 extra_json TEXT NULL，承载本次 attempt 的
--   渠道扩展属性（当前键：channelMode = MOCK | SANDBOX）。
--
-- 为什么不是专用列 channel_mode：
--   曾计划新增 `channel_mode` 专用列（H2 前的方案），裁决后改为 extra_json 的
--   channelMode 键——模态是**渠道侧的扩展属性**，不是 payment 域的一等字段；
--   用 JSON 载体避免每加一个渠道属性就 ALTER 一次表（FR-300 / FR-301②）。
--
-- 幂等可重放：沿用 015/018/019 模式（information_schema 守卫 + PREPARE 动态 SQL）。
-- 禁用 `ADD COLUMN IF NOT EXISTS`（MariaDB 方言，MySQL 8 报错——016 教训）。
--
-- 刻意不做（FR-307 / FR-308 / FR-309）：
--   · **不回填**存量行——extra_json 为 NULL 时读取侧一律判为 MOCK（fail-safe），
--     回填既无必要也会伪造「已确认模态」的事实；
--   · **不建索引**——本 Feature 不做按模态的 SQL 统计；
--   · **不改**渠道归属判定——归属恒读 payment_attempts.channel_code 列。
--
-- 用法：全新库无需执行（03-payment-schema.sql 已含该列）；存量库执行本脚本。
-- =============================================================================

USE `payment`;

SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'extra_json') = 0,
  'ALTER TABLE payment_attempts ADD COLUMN extra_json TEXT NULL COMMENT ''渠道扩展属性 JSON（spec 030；当前键 channelMode=MOCK|SANDBOX）'' AFTER version',
  'SELECT ''payment_attempts.extra_json 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
