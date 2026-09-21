-- 本地开发 MySQL 初始化：只创建 9 个空数据库（Database-per-Service），不创建任何业务表。
-- 表结构由后续各服务自己的 migration 负责（见 deployment/README.md「后续 schema / migration 位置」）。
-- merchant-service 无独立数据库（内存仓储，无数据源）。
-- 031 勘误（spec §14⑧ code-debt #1）：补 ledger——原清单遗漏，ledger 库此前仅靠
-- 09-ledger-schema.sql 的 CREATE DATABASE 兜底。

CREATE DATABASE IF NOT EXISTS `catalog`        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `order`          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `payment`        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `refund`         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `fulfillment`    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `entitlement`    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `reconciliation` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `settlement`     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `ledger`         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
