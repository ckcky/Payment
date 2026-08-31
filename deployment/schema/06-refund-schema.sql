-- 退款服务自有 Schema（Database-per-Service）：refunds / refund_items。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。
--
-- ADR-0016 已否决（负责人决议「部分退款不做」）：refunds 不再有 refunded_amount_minor 列。
-- 已部署环境需手工执行下迁移（MySQL 不支持 DROP COLUMN IF EXISTS，故不写成幂等语句）：
--   ALTER TABLE `refund`.`refunds` DROP COLUMN `refunded_amount_minor`;
-- 全额退款语义下该列无信息量（恒等于 amount_minor 或 0），丢弃不丢数据。

CREATE DATABASE IF NOT EXISTS `refund` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `refund`;

CREATE TABLE IF NOT EXISTS refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id VARCHAR(64) NOT NULL,
    payment_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refunds_idempotency_key (idempotency_key),
    KEY idx_refunds_payment_id (payment_id),
    KEY idx_refunds_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS refund_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    order_item_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_refund_items_refund_id (refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 退款受理悲观锁：以 payment_id 为行锁，串行化同一支付的退款受理，
-- 防止并发读累计退款金额 + 写入之间的竞态导致超退款（H1 资金正确性）。
-- 行在事务内由 INSERT ... ON DUPLICATE KEY UPDATE 持有直至提交/回滚。
CREATE TABLE IF NOT EXISTS refund_intake_locks (
    payment_id BIGINT NOT NULL,
    PRIMARY KEY (payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 退款后处理尝试记录（ADR-0017）：每次后处理目标（履约/权益/记账）一次调用的结果，
-- 失败不回滚退款成功事实（Saga），但 MUST 留下可追溯记录供运营按 refund_id 查询与重放。
CREATE TABLE IF NOT EXISTS refund_post_process_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    target VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(512),
    attempt_count INT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rppa_refund_target (refund_id, target),
    KEY idx_rppa_refund_id (refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
