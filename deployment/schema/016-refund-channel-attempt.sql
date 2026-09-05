-- Feature 016 / ADR-0054 / FR-017：payment_attempts 复用为渠道交互记录表，
-- 退款尝试也落此表（payment_no 关联 + channel_reference = 渠道退款流水号，唯一约束兜底），
-- 修复「退款渠道流水号被丢弃」对账缺口（N4）。
-- 作用：payment_attempts 增加 attempt_type 列区分支付/退款尝试；存量行默认 PAYMENT。
-- 兼容性：MySQL 不支持 ADD COLUMN/INDEX IF NOT EXISTS，改用 INFORMATION_SCHEMA 判空的幂等写法。

DELIMITER $$
DROP PROCEDURE IF EXISTS mig_016_refund_attempt $$
CREATE PROCEDURE mig_016_refund_attempt()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts'
          AND COLUMN_NAME = 'attempt_type'
    ) THEN
        ALTER TABLE payment_attempts
            ADD COLUMN attempt_type VARCHAR(16) NOT NULL DEFAULT 'PAYMENT';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts'
          AND INDEX_NAME = 'idx_attempts_payment_type'
    ) THEN
        ALTER TABLE payment_attempts
            ADD INDEX idx_attempts_payment_type (payment_no, attempt_type);
    END IF;
END $$
CALL mig_016_refund_attempt() $$
DROP PROCEDURE IF EXISTS mig_016_refund_attempt $$
DELIMITER ;
