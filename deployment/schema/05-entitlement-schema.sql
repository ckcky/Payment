-- 权益服务自有 Schema（Database-per-Service）：entitlements。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `entitlement` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `entitlement`;

CREATE TABLE IF NOT EXISTS entitlements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
    source_fulfillment_id VARCHAR(64) NOT NULL,
    grant_ref VARCHAR(64),
    available_quantity INT NOT NULL,
    scope VARCHAR(64) NOT NULL,
    expiry_at DATETIME,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entitlements_source_fulfillment_id (source_fulfillment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
