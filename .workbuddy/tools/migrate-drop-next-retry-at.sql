-- ADR-0013 修订：重试改为请求内联，payment_attempts 不再需要重试调度载体 next_retry_at。
USE `payment`;
ALTER TABLE payment_attempts DROP INDEX idx_attempts_next_retry_at;
ALTER TABLE payment_attempts DROP COLUMN next_retry_at;
