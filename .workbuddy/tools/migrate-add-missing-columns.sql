-- 把本机既有的开发库补齐到 deployment/schema/*.sql 的当前结构。
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行时已存在的列会报 ERR，可忽略。
USE `payment`;
ALTER TABLE payments        ADD COLUMN query_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE payments        ADD COLUMN entered_unknown_at DATETIME NULL;
ALTER TABLE payment_attempts ADD COLUMN error_type VARCHAR(16) NULL;
