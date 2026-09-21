-- 支付服务自有 Schema（Database-per-Service）：payments / payment_attempts。
-- 单机开发由 docker-compose 的 MySQL 8 实例承载（多库共实例，服务间不共享表）。

CREATE DATABASE IF NOT EXISTS `payment` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `payment`;

CREATE TABLE IF NOT EXISTS payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(32) NOT NULL COMMENT '业务单号 PM+雪花（ADR-0062）',
    idempotency_key VARCHAR(128) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
    user_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NULL COMMENT '商户号（spec 031 / §13，H11 前置收编）：PAYMENT_CAPTURE 事实锚；历史行 NULL',
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    attempt_seq INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    current_attempt_id BIGINT,
    failure_reason VARCHAR(255),
    query_attempts INT NOT NULL DEFAULT 0,
    entered_unknown_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_idempotency_key (idempotency_key),
    UNIQUE KEY uk_payments_payment_no (payment_no),
    KEY idx_payments_transaction_id (transaction_id),
    KEY idx_payments_txn_seq (transaction_id, attempt_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_no VARCHAR(32) NOT NULL COMMENT '所属支付单（业务单号 PM+雪花，ADR-0063）',
    channel_code VARCHAR(32) NOT NULL,
    attempt_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT' COMMENT '尝试类型 PAYMENT/REFUND（Feature 016 / FR-017）',
    amount_minor BIGINT NOT NULL COMMENT '资金口径：PAYMENT=支付金额；REFUND=所属支付单金额（spec 018 / FR-002）',
    currency_code VARCHAR(8) NOT NULL,
    channel_reference VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    retry_count INT NOT NULL DEFAULT 0,
    -- 错误分类（由双响应码派生，仅观测用；重试判定不读它，ADR-0012/0013）
    error_type VARCHAR(16) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    requested_at DATETIME NOT NULL,
    responded_at DATETIME NULL,
    version INT NOT NULL DEFAULT 1,
    -- spec 030 / FR-301①：渠道扩展属性（JSON 文本）。当前承载 channelMode（MOCK/SANDBOX），
    -- 供**反向路径**（查询 / 退款 / 超时扫描）判读本次 attempt 的渠道模态。
    -- 刻意用 TEXT 存 JSON 而非 MySQL 原生 JSON 类型（沿用 payload_json 先例）；
    -- 刻意**不建索引、不参与渠道归属判定**——归属恒读 channel_code 列（FR-305/FR-309）。
    extra_json TEXT NULL COMMENT '渠道扩展属性 JSON（spec 030；当前键 channelMode=MOCK|SANDBOX）',
    PRIMARY KEY (id),
    KEY idx_attempts_payment_no (payment_no),
    KEY idx_attempts_payment_type (payment_no, attempt_type),
    UNIQUE KEY uk_attempts_channel_reference (channel_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =============================================================================
-- Feature 015 / P3（ADR-0064）：退款域并入 payment-service
-- 原 refund 库 4 张表随域迁入 payment 库（com.payment.refund 代码已同迁）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_no VARCHAR(32) NOT NULL COMMENT '业务单号 PMRF+雪花（ADR-0062/0067；存量 RF 保留不改写）',
    transaction_refund_no VARCHAR(32) NULL COMMENT '上层交易退款单 TXRF（spec 019 / ADR-0067；幂等键载体）',
    transaction_no VARCHAR(32) NULL COMMENT '所属交易单 TX（spec 019 / ADR-0067；回调通知 order 时回传）',
    idempotency_key VARCHAR(128) NOT NULL,
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（业务单号 OR+雪花，ADR-0063）',
    payment_no VARCHAR(32) NOT NULL COMMENT '所属支付单（业务单号 PM+雪花，ADR-0063）',
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refunds_idempotency_key (idempotency_key),
    UNIQUE KEY uk_refunds_refund_no (refund_no),
    KEY idx_refunds_payment_no (payment_no),
    KEY idx_refunds_order_no (order_no),
    KEY idx_refunds_transaction_refund_no (transaction_refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS refund_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_no VARCHAR(32) NOT NULL COMMENT '所属退款（业务单号 RF+雪花，ADR-0062/0063）',
    order_item_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_refund_items_refund_no (refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 退款受理悲观锁：以 payment_no 为行锁，串行化同一支付的退款受理（H1 资金正确性）。
CREATE TABLE IF NOT EXISTS refund_intake_locks (
    payment_no VARCHAR(32) NOT NULL COMMENT '所属支付单（业务单号 PM+雪花，ADR-0063）',
    PRIMARY KEY (payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 退款后处理尝试记录（ADR-0017）：失败不回滚退款成功事实（Saga），留痕供重放。
CREATE TABLE IF NOT EXISTS refund_post_process_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_no VARCHAR(32) NOT NULL COMMENT '所属退款（业务单号 RF+雪花，ADR-0062/0063）',
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
    UNIQUE KEY uk_rppa_refund_target (refund_no, target),
    KEY idx_rppa_refund_no (refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- spec 031 §12 / 034 §9：出站失败台账（调用方侧轻量台账，补偿辅助而非资金事实源，
-- 资金事实恒以 ledger_transactions 为准）。同一事实反复失败只留一行
-- （UNIQUE(event_type, source_id)），retry_count 递增；耗尽置 ABANDONED，
-- 人工 replay 重置 retry_count（spec 034 §12.1）。
CREATE TABLE IF NOT EXISTS pending_postings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型：PAYMENT_CAPTURE/REFUND/ORDER_NOTIFY_SUCCEEDED/ORDER_NOTIFY_REFUND_RESULT',
    source_type VARCHAR(32) NOT NULL COMMENT '来源域：PAYMENT/REFUND/ORDER',
    source_id VARCHAR(64) NOT NULL COMMENT '来源业务单号（ADR-0063）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '记账事件派生键 {eventType}:{sourceId}（031 原则 10）；通知类同型',
    payload_json TEXT NOT NULL COMMENT '重放载荷（原样重发，不重算派生键）',
    fail_reason VARCHAR(512) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/REPOSTED/ABANDONED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_postings_event_source (event_type, source_id),
    KEY idx_pending_postings_status (status, retry_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
