-- =============================================================================
-- Feature 037 迁移：payment 库 payment_attempts 加 channel_no（渠道网关业务单号）
--
-- 作用：
--   payment 库 payment_attempts 加 channel_no VARCHAR(32) NOT NULL + UNIQUE，
--   承载渠道网关自己的业务单号（CH + 雪花）。
--
-- 为什么需要它（FR-001 / FR-002）：
--   渠道网关 MUST 拥有自己的业务单号，取代跨域契约中的数值主键 attemptId。
--   payment_attempts.id 是数据库自增主键，把它递给渠道即违反 ADR-0063
--   「跨系统标识一律业务单号，禁止数值 ID」——历史已踩过 C-12 / S21 事故
--   （错把平台单号当渠道单号传）。
--
-- 幂等可重放：沿用 030 / 033 模式（information_schema 守卫 + PREPARE 动态 SQL）。
-- 禁用 `ADD COLUMN IF NOT EXISTS`（MariaDB 方言，MySQL 8 报错——016 教训）。
--
-- ⚠️ 存量行处理（与 FR-002 / D3 的关系）：
--   spec 037 / D3 明确「系统未上线，无存量数据，**不回填**」——本脚本对**生产**无影响。
--   但开发/演示卷里可能有历史演示行（如 audit-faults.sql 注入的 4 行），
--   而 NOT NULL + UNIQUE 无法直接加在含多行 NULL 的列上（UNIQUE 允许多个 NULL，
--   但 NOT NULL 会让 ALTER 直接失败）。故这里对**存量行**补一个确定性占位单号
--   （CH + LPAD(id,18,'0')），随后立即收窄为 NOT NULL 并建唯一索引。
--   reset.sh 在第 2 步会 TRUNCATE 掉这些占位行，因此占位值不会进入任何演示事实。
--
-- 用法：全新库无需执行（03-payment-schema.sql 已含该列）；存量库执行本脚本。
-- =============================================================================

USE `payment`;

-- ① 加列（先可空，避免存量行触发 NOT NULL 失败）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'channel_no') = 0,
  'ALTER TABLE payment_attempts ADD COLUMN channel_no VARCHAR(32) NULL COMMENT ''渠道网关业务单号 CH+雪花（spec 037 / FR-001）'' AFTER payment_no',
  'SELECT ''payment_attempts.channel_no 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② 存量行补确定性占位单号（仅开发/演示卷；生产无存量，见 D3）
UPDATE payment_attempts
   SET channel_no = CONCAT('CH', LPAD(id, 18, '0'))
 WHERE channel_no IS NULL;

-- ③ 收窄为 NOT NULL（与 03-payment-schema.sql 的列定义对齐）
SET @sql = IF (
  (SELECT IS_NULLABLE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'channel_no') = 'YES',
  'ALTER TABLE payment_attempts MODIFY COLUMN channel_no VARCHAR(32) NOT NULL COMMENT ''渠道网关业务单号 CH+雪花（spec 037 / FR-001）''',
  'SELECT ''payment_attempts.channel_no 已为 NOT NULL'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ④ 唯一索引（FR-002：网关单号全局唯一）
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts'
      AND INDEX_NAME = 'uk_attempts_channel_no') = 0,
  'ALTER TABLE payment_attempts ADD UNIQUE KEY uk_attempts_channel_no (channel_no)',
  'SELECT ''uk_attempts_channel_no 已存在'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
