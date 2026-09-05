-- Feature 016 / ADR-0054 / FR-017：payment_attempts 复用为渠道交互记录表，
-- 退款尝试也落此表（payment_no 关联 + channel_reference = 渠道退款流水号，唯一约束兜底），
-- 修复「退款渠道流水号被丢弃」对账缺口（N4）。
-- 作用：payment_attempts 增加 attempt_type 列区分支付/退款尝试；存量行默认 PAYMENT。

ALTER TABLE payment_attempts
    ADD COLUMN IF NOT EXISTS attempt_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT';

-- 退款事实按 (payment_no, attempt_type) 定位退款渠道尝试
ALTER TABLE payment_attempts
    ADD INDEX IF NOT EXISTS idx_attempts_payment_type (payment_no, attempt_type);
