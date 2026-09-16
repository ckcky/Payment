-- =============================================================================
-- spec 027 / ADR-0071：用户支付限额（日月年周期额度与两阶段预占）
--
-- 三张表随域落在 payment 库（Database-per-Service，与 payments / payment_attempts 同库）：
--   user_payment_limits  限额配置（无行 = 不限额，FR-012）
--   user_limit_usage     周期占用（双金额：used_minor 已确认 + pending_minor 在途）
--   limit_operations     幂等流水（UK(biz_no, op_type) 是幂等的数据库级兜底，INV-4）
--
-- 列序守 spec 018 / ADR-0066：自增 id → 业务主键 → 唯一索引列 → 其余列 → 审计列 → version。
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行。
-- 注：MySQL 8 语法（COMMENT / ENGINE / utf8mb4）；H2 侧见 payment-service/src/test/resources/schema.sql。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `payment` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `payment`;

-- -----------------------------------------------------------------------------
-- 1. user_payment_limits —— 限额配置
--    一行承载日 / 月 / 年三个额度；0 = 该周期不限（FR-012）。
--    「查不到行」= 不限额，与「三个额度都是 0」等价（INV-2 / FR-024 兼容性的硬要求）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_payment_limits (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL COMMENT '用户标识（与 payments.user_id 同口径）',
    currency_code VARCHAR(8) NOT NULL COMMENT '币种（ADR-0010 最小货币单位）',
    daily_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '日限额（分）；0=不限',
    monthly_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '月限额（分）；0=不限',
    yearly_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '年限额（分）；0=不限',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DISABLED',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_uplimit_user_ccy (user_id, currency_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户支付限额配置（spec 027 / ADR-0071）；无行=不限额（FR-012）';

-- -----------------------------------------------------------------------------
-- 2. user_limit_usage —— 周期占用（双金额）
--    只统计 used 会被并发击穿（N 笔同时读到「未超限」），故必须有 pending 在途占用。
--    period_start 存该行所属周期起始日；跨周期现算建新行，旧行自然闲置（D10，零调度器）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_limit_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL COMMENT 'DAY | MONTH | YEAR',
    period_start DATE NOT NULL COMMENT '该行所属周期起始日；跨周期现算建新行，旧行自然闲置',
    used_minor BIGINT NOT NULL DEFAULT 0 COMMENT '已确认（支付 SUCCEEDED）',
    pending_minor BIGINT NOT NULL DEFAULT 0 COMMENT '在途占用（已预占未终态）',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ulusage_user_ccy_period (user_id, currency_code, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户周期额度占用（spec 027 / ADR-0071）；周期重置靠 period_start 现算（D10）';

-- -----------------------------------------------------------------------------
-- 3. limit_operations —— 幂等流水（含过期的审计兜底）
--    UK(biz_no, op_type, period)：一张支付单在**每个周期**上每种操作只允许一条流水，撞键即跳过（INV-4）。
--    为什么必须带 period：一笔支付同时预占日/月/年三个周期，若 UK 只有 (biz_no, op_type)，
--    第一个周期插入的 RESERVE 会把后两个周期一并判为「已执行」→ 月/年额度根本不会被占用（限额静默失效）。
--    expires_at 仅是 RESERVE 的审计留痕；日常过期判定走 Redis TTL（D13），不读此列做扫描。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS limit_operations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    operation_no VARCHAR(32) NOT NULL COMMENT '业务单号 LO+雪花（ADR-0062）',
    biz_no VARCHAR(32) NOT NULL COMMENT '关联支付单 paymentNo（ADR-0063）',
    op_type VARCHAR(16) NOT NULL COMMENT 'RESERVE | CONFIRM | RELEASE | EXPIRED',
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL COMMENT '操作金额（分）',
    expires_at DATETIME NULL COMMENT '仅 RESERVE：在途到期时刻（审计兜底），日常判定走 Redis',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_limitop_biz_type (biz_no, op_type, period),
    KEY idx_limitop_user_type (user_id, op_type),
    KEY idx_limitop_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='额度操作流水（spec 027 / ADR-0071）；UK(biz_no,op_type,period) 是幂等的数据库级兜底（INV-4）';
