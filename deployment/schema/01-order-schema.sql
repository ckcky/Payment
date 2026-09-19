-- 订单服务自有 Schema（Database-per-Service）：orders / order_items / transactions。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `order` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `order`;

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL COMMENT '业务单号 OR+雪花（ADR-0062）',
    user_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    payment_no VARCHAR(32) NULL COMMENT '关联支付单（业务单号 PM+雪花，ADR-0063）',
    status VARCHAR(32) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    total_minor BIGINT NOT NULL,
    paid_minor BIGINT NOT NULL DEFAULT 0,
    refunded_minor BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_item_no VARCHAR(32) NOT NULL COMMENT '明细业务单号 OI+雪花（spec 018 / ADR-0066，跨服务引用标识，ADR-0063）',
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
    sku_id VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    price_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_items_order_item_no (order_item_no),
    KEY idx_order_items_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_no VARCHAR(32) NOT NULL COMMENT '业务单号 TX+雪花（ADR-0062）',
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payment_no VARCHAR(32) NULL COMMENT '生效支付单：首张成功支付（spec 019 / ADR-0067；surplus 被退单不覆盖）',
    refunded_minor BIGINT NOT NULL DEFAULT 0 COMMENT '累计已退金额（spec 019 / ADR-0067）',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transactions_order_no (order_no),
    UNIQUE KEY uk_transactions_transaction_no (transaction_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- spec 019 / ADR-0067：交易层退款单（TXRF），order 驱动两层退款单的上层单
CREATE TABLE IF NOT EXISTS transaction_refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_no VARCHAR(32) NOT NULL COMMENT '交易层退款单号 TXRF+雪花（ADR-0062/0067）',
    payment_refund_no VARCHAR(32) NULL COMMENT '支付层退款执行单号 PMRF+雪花（payment 响应回填，ADR-0067）',
    transaction_no VARCHAR(32) NOT NULL COMMENT '所属交易（TX+雪花，ADR-0062）',
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（OR+雪花，ADR-0063）',
    payment_no VARCHAR(32) NOT NULL COMMENT '被退支付单（PM+雪花，ADR-0063）',
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'REQUESTED/PROCESSING/SUCCEEDED/FAILED/REJECTED',
    reason VARCHAR(255) NOT NULL,
    failure_reason VARCHAR(255) NULL,
    idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键=TXRF（同号重试可重入回放）',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_refunds_refund_no (refund_no),
    UNIQUE KEY uk_transaction_refunds_idempotency_key (idempotency_key),
    KEY idx_transaction_refunds_transaction_no (transaction_no),
    KEY idx_transaction_refunds_payment_refund_no (payment_refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- spec 029 / FR-401、T51：订单事件轨迹（只读投影，业务正确性 MUST NOT 依赖它，INV-5）
CREATE TABLE IF NOT EXISTS order_event_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（OR+雪花）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型（topic 名，如 order.paid）',
    topic VARCHAR(64) NOT NULL COMMENT '消息主题',
    msg_id VARCHAR(64) NOT NULL COMMENT '信封 msgId（重复投递去重）',
    trace_id VARCHAR(64) NULL COMMENT '链路 traceId（跨异步边界连续）',
    producer VARCHAR(64) NULL COMMENT '生产方服务名',
    payload_json TEXT NULL COMMENT '事件负载 JSON',
    occurred_at DATETIME NOT NULL COMMENT '事件发生时刻（生产端 occurredAt）',
    created_at DATETIME NOT NULL COMMENT '轨迹落表时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_event_log_msg_id (msg_id),
    KEY idx_order_event_log_order_occurred (order_no, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
