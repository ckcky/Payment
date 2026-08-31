-- 目录服务自有 Schema（Database-per-Service）：products / skus / stock / stock_reservation。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `catalog` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `catalog`;

CREATE TABLE IF NOT EXISTS products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_products_product_code (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skus (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku_code VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    price_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    delivery_definition VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skus_sku_code (sku_code),
    KEY idx_skus_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 库存聚合：total = available + reserved + sold（不变量由领域层强制）。
-- reserved 表示已被订单预占但尚未支付确认；sold 表示已扣减（支付成功）。
CREATE TABLE IF NOT EXISTS stock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    total BIGINT NOT NULL,
    available BIGINT NOT NULL,
    reserved BIGINT NOT NULL,
    sold BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 预占记录：每次下单预占一行，幂等键 reservation_id；支付成功→CONFIRMED，失败/超时→RELEASED。
CREATE TABLE IF NOT EXISTS stock_reservation (
    reservation_id VARCHAR(64) NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    deduct_id VARCHAR(64),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (reservation_id),
    KEY idx_stock_reservation_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
