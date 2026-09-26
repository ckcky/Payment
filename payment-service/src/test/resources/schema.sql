DROP TABLE IF EXISTS channel_orders;
DROP TABLE IF EXISTS payments;

CREATE TABLE payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    attempt_seq INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    current_attempt_id BIGINT,
    failure_reason VARCHAR(255),
    query_attempts INT NOT NULL DEFAULT 0,
    entered_unknown_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key)
);
CREATE INDEX idx_payments_txn_seq ON payments (transaction_id, attempt_seq);

-- spec 041：渠道单表（原 payment_attempts，随 ChannelOrder 迁入渠道网关域后正名）。
-- 生产侧由 03-payment-schema.sql 建 payment_attempts 再由 041-channel-orders-rename.sql 改名；
-- 此处是 H2 测试镜像，直接建成终态名（H2 镜像不会自动同步迁移脚本，见 037 的同类说明）。
CREATE TABLE channel_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no VARCHAR(32) NOT NULL,
    -- spec 037 / FR-001 / FR-002：渠道网关业务单号（CH+雪花），NOT NULL + UNIQUE。
    -- 与 deployment/schema/03-payment-schema.sql 手工同步（H2 镜像不会自动同步）。
    channel_no VARCHAR(32) NOT NULL,
    channel_code VARCHAR(32) NOT NULL,
    attempt_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT',
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    channel_reference VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    retry_count INT NOT NULL DEFAULT 0,
    error_type VARCHAR(16) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    requested_at TIMESTAMP NOT NULL,
    responded_at TIMESTAMP,
    version INT NOT NULL DEFAULT 1,
    extra_json TEXT NULL,
    CONSTRAINT uk_attempts_channel_no UNIQUE (channel_no),
    CONSTRAINT uk_attempts_channel_reference UNIQUE (channel_reference)
);

-- Feature 015 / P3：退款域并入 payment-service（表随域迁入同一 H2 库）
DROP TABLE IF EXISTS refund_items;
DROP TABLE IF EXISTS refund_intake_locks;
DROP TABLE IF EXISTS refund_post_process_attempts;
DROP TABLE IF EXISTS refunds;
-- spec 027 / ADR-0071：用户支付限额三表（DDL 与 deployment/schema/027-user-payment-limit.sql 对齐）
DROP TABLE IF EXISTS limit_operations;
DROP TABLE IF EXISTS user_limit_usage;
DROP TABLE IF EXISTS user_payment_limits;

CREATE TABLE refunds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_no VARCHAR(32) NOT NULL,
    transaction_refund_no VARCHAR(32) NULL,
    transaction_no VARCHAR(32) NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    payment_no VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_refunds_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uk_refunds_refund_no UNIQUE (refund_no)
);

CREATE TABLE refund_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    order_item_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1
);

CREATE TABLE refund_intake_locks (
    payment_no VARCHAR(32) NOT NULL PRIMARY KEY
);

CREATE TABLE refund_post_process_attempts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    target VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(512),
    attempt_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_rppa_refund_target UNIQUE (refund_id, target)
);

-- =============================================================================
-- spec 027 / ADR-0071：用户支付限额（H2 方言；与 deployment/schema/027-user-payment-limit.sql 同构）
-- 周期重置靠 period_start 现算（D10）；limit_operations 的 UK 是幂等的数据库级兜底（INV-4）。
-- =============================================================================

CREATE TABLE user_payment_limits (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    daily_limit_minor BIGINT NOT NULL DEFAULT 0,
    monthly_limit_minor BIGINT NOT NULL DEFAULT 0,
    yearly_limit_minor BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_uplimit_user_ccy UNIQUE (user_id, currency_code)
);

CREATE TABLE user_limit_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL,
    period_start DATE NOT NULL,
    used_minor BIGINT NOT NULL DEFAULT 0,
    pending_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    CONSTRAINT uk_ulusage_user_ccy_period UNIQUE (user_id, currency_code, period)
);

CREATE TABLE limit_operations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_no VARCHAR(32) NOT NULL,
    biz_no VARCHAR(32) NOT NULL,
    op_type VARCHAR(16) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    period VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL,
    expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_limitop_biz_type UNIQUE (biz_no, op_type, period)
);
CREATE INDEX idx_limitop_user_type ON limit_operations (user_id, op_type);
CREATE INDEX idx_limitop_expiry ON limit_operations (expires_at);
