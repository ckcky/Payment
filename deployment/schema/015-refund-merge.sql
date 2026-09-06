-- =============================================================================
-- 015-refund-merge.sql — Feature 015 / P3（ADR-0064）存量环境迁移脚本（可重放）
--
-- 目标：把原 `refund` 库 4 张表迁入 `payment` 库，供「退款域并入 payment-service」
-- 的存量部署环境执行。全新环境无需执行（03-payment-schema.sql 已含全部表）。
--
-- 可重放性：先查 information_schema 判断表是否已存在于 payment 库，
-- 已存在则跳过建表（用动态 SQL 组装）。
--
-- 数据搬迁（可选）：如需保留历史退款数据，取消下方 INSERT...SELECT 注释后执行
-- （依赖同实例、字段一致；列差异见 03-payment-schema.sql 注释）。
-- =============================================================================
USE `payment`;

-- ---- refunds ----
SET @sql = IF (
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = 'payment' AND table_name = 'refunds') = 0,
    'CREATE TABLE refunds LIKE `refund`.refunds',
    'SELECT ''refunds already in payment schema'' AS notice'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 数据搬迁（可选）：INSERT INTO refunds SELECT * FROM `refund`.refunds;

-- ---- refund_items ----
SET @sql = IF (
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = 'payment' AND table_name = 'refund_items') = 0,
    'CREATE TABLE refund_items LIKE `refund`.refund_items',
    'SELECT ''refund_items already in payment schema'' AS notice'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 数据搬迁（可选）：INSERT INTO refund_items SELECT * FROM `refund`.refund_items;

-- ---- refund_intake_locks ----
SET @sql = IF (
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = 'payment' AND table_name = 'refund_intake_locks') = 0,
    'CREATE TABLE refund_intake_locks LIKE `refund`.refund_intake_locks',
    'SELECT ''refund_intake_locks already in payment schema'' AS notice'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 数据搬迁（可选）：INSERT INTO refund_intake_locks SELECT * FROM `refund`.refund_intake_locks;

-- ---- refund_post_process_attempts ----
SET @sql = IF (
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = 'payment' AND table_name = 'refund_post_process_attempts') = 0,
    'CREATE TABLE refund_post_process_attempts LIKE `refund`.refund_post_process_attempts',
    'SELECT ''refund_post_process_attempts already in payment schema'' AS notice'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 数据搬迁（可选）：INSERT INTO refund_post_process_attempts SELECT * FROM `refund`.refund_post_process_attempts;

-- 迁表并核验完成后，旧库退役：
-- DROP DATABASE `refund`;
