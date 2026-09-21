-- 本地开发 MySQL 初始化：只创建 9 个空数据库（Database-per-Service），不创建任何业务表。
-- 表结构由后续各服务自己的 migration 负责（见 deployment/README.md「后续 schema / migration 位置」）。
-- merchant-service 无独立数据库（内存仓储，无数据源）。
-- 031 勘误（spec §14⑧ code-debt #1）：补 ledger——原清单遗漏，ledger 库此前仅靠
-- 09-ledger-schema.sql 的 CREATE DATABASE 兜底。
-- 033 遗留标注（H-033-3，ADR-0081 决策 2）：`refund` 库已随 015/P3 并入 payment（06-refund-schema.sql
-- 仅存量库兼容保留）——**保留不删**（误伤存量库的风险大于清理收益），是否清理另立 chore；
-- 本清单与 deployment/schema 的库集合一致性由 schema-lint.sh L-2 守卫（refund 在豁免清单内）。

CREATE DATABASE IF NOT EXISTS `catalog`        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `order`          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `payment`        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `refund`         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `fulfillment`    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `entitlement`    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `reconciliation` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `settlement`     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `ledger`         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
