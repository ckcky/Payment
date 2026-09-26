-- =============================================================================
-- Feature 041 迁移：payment 库 payment_attempts → channel_orders（渠道单正名）
--
-- 作用：
--   把 payment_attempts 表重命名为 channel_orders，让库表名与重构后的领域口径一致
--   ——这张表记的是**渠道层的订单**（一次渠道交互的事实载体），不是「支付单的尝试记录」。
--
-- 为什么需要它（spec 041 / D1）：
--   重构前渠道单的领域模型（PaymentAttempt）与表（payment_attempts）都挂在 payment 域，
--   由 PaymentPersistence 在同一事务里代开、代收敛、代落库；
--   重构后它归渠道网关域（ChannelOrder / ChannelOrderRepository /
--   channelgateway.infra.persistence），表名也必须跟着正名——
--   否则「payment_xxx 表由渠道域独占写」这句话在库里就是自相矛盾的。
--
-- 幂等可重放：沿用 030 / 033 / 037 模式（information_schema 守卫 + PREPARE 动态 SQL）。
--   · 全新库：03-payment-schema.sql 先建出 payment_attempts，本脚本在最后把它改名；
--   · 存量库：baseline + 增量演进后同样落在 payment_attempts，本脚本改名收敛。
--   两条路径结果一致（schema-replay.sh 的 A/B 结构快照门禁据此成立）。
--
-- ⚠️ 索引名**刻意不改**（仍是 uk_attempts_channel_no / idx_attempts_payment_no 等）：
--   RENAME TABLE 不迁移索引名，而存量库的索引是 016 / 037 用旧表名加上去的。
--   若这里顺手把索引名改成 uk_channel_orders_*，全新库与存量库的结构快照就会 diff，
--   schema-replay.sh 的双路径一致性门禁立刻失败。索引名是表内命名空间，与表名无关。
--
-- 用法：全新库与存量库都由 reset.sh / schema-replay.sh 自动重放，无需手工执行。
-- =============================================================================

USE `payment`;

-- ① 重命名（仅当「旧表在、新表不在」时执行）——把两条路径都收敛到 channel_orders
SET @sql = IF (
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'channel_orders') = 0
  AND (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts') = 1,
  'RENAME TABLE payment_attempts TO channel_orders',
  'SELECT ''channel_orders 已就位（无需重命名）'' AS note');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
