-- =============================================================================
-- Feature 034 迁移：出站失败台账 pending_postings（spec 031 §12 设计 / 034 §9 实现归属）
--
-- 作用：payment / settlement / reconciliation 三个**调用方 schema** 各加一张
--   `pending_postings`（台账是补偿辅助而非资金事实源，资金事实恒以 ledger_transactions 为准）。
--   Ledger 自身不加表（"Ledger 不可用时写 Ledger 自己没意义"，031 §12 裁决原文）。
--
-- 表结构（031 §12 规范形状 + 034 §9.2 M7 泛化）：
--   UNIQUE(event_type, source_id) —— 同一事实反复失败只留一行，retry_count 递增；
--   payload_json —— 重放 = 原样重发（与首次请求逐字节一致，杜绝二次漂移）；
--   status PENDING/REPOSTED/ABANDONED —— 重放成功 / 耗尽均为终态，无无限 PENDING。
--
-- 幂等可重放：纯 CREATE TABLE IF NOT EXISTS（无 ALTER，守卫不需要）；
--   全新库无需本脚本（03/07/08 全量 schema 已是目标形态，本脚本各项均为无害 no-op）；
--   存量库由本脚本就地演进。三处 CREATE TABLE 语句与各全量 schema 逐字一致
--   （schema-replay 双路径快照 S_A == S_B 的前提）。
-- =============================================================================

USE `payment`;

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

USE `settlement`;

CREATE TABLE IF NOT EXISTS pending_postings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型：MERCHANT_SETTLEMENT',
    source_type VARCHAR(32) NOT NULL COMMENT '来源域：SETTLEMENT',
    source_id VARCHAR(64) NOT NULL COMMENT '来源业务单号（ADR-0063）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '记账事件派生键 {eventType}:{sourceId}（031 原则 10）',
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

USE `reconciliation`;

CREATE TABLE IF NOT EXISTS pending_postings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型：ADJUSTMENT',
    source_type VARCHAR(32) NOT NULL COMMENT '来源域：RECONCILIATION',
    source_id VARCHAR(64) NOT NULL COMMENT '来源业务单号（ADR-0063）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '记账事件派生键 {eventType}:{sourceId}（031 原则 10）',
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
