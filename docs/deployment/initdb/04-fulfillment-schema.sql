-- 履约服务自有 Schema（Database-per-Service）：fulfillments。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `fulfillment` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `fulfillment`;

CREATE TABLE IF NOT EXISTS fulfillments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id VARCHAR(64) NOT NULL,
    order_item_id VARCHAR(64),
    delivery_content VARCHAR(255) NOT NULL,
    source_payment_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fulfillments_source_payment_id (source_payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
