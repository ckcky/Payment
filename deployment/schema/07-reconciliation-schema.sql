-- 对账服务自有 Schema（Database-per-Service）：reconciliation_batches。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。
-- 匹配/差异以 JSON 内嵌于批次（matches_json / differences_json），避免对账结果拆表带来跨表一致性成本。

CREATE DATABASE IF NOT EXISTS `reconciliation` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `reconciliation`;

CREATE TABLE IF NOT EXISTS reconciliation_batches (
    id BIGINT NOT NULL AUTO_INCREMENT,
    period VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    matches_json TEXT,
    differences_json TEXT,
    closed_at DATETIME NULL,
    closed_by VARCHAR(64) NULL,
    statement_source VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_batches_period (period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
