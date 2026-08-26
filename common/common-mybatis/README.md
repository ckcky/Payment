# 持久化约定（common-mybatis / 各服务 infra/persistence）

> 对应 tasks.md **T014**。本工程采用 **Database-per-Service**（ADR-0001）：每个服务自有数据库
> 与自有表，服务之间通过 **API（Feign，同步 RPC）** 通信，不使用跨服务异步事件或 MQ。

## 铁律（MUST）

1. **数据所有权**：每个服务的表只由该服务自己读写；**禁止** 任何服务 SQL 他服务表（Constitution §2.3 领域边界）。
2. **不建 Repository Framework**：直接使用 MyBatis-Plus `BaseMapper`；仓储接口按模块放在各服务
   `infra/persistence/<module>/`，不抽象出通用仓储层。
3. **关键事实可追溯**：资金/状态变更必须落审计字段（`BaseEntity` 的 `createdAt/updatedAt/createdBy/updatedBy`）
   + 乐观锁 `version`；支付/退款等资金入口额外保存回调历史与幂等键（见各服务 infra 层）。
4. **乐观锁并发安全**：所有会被并发状态迁移的表，实体继承 `BaseEntity` 并依赖 `@Version` 乐观锁，
   禁止「无锁直改 status」。

## 实体约定

- 继承 `com.payment.common.mybatis.BaseEntity`，自动获得 `id / createdAt / updatedAt / createdBy / updatedBy / version`。
- 金额列一律 `BIGINT`（最小货币单位）或 `DECIMAL(18,2)`，**禁止** `FLOAT/DOUBLE`；实体里用 `long`/`BigDecimal`。
- 状态列用显式枚举字符串，**禁止** 裸 `String` 状态散落（状态机在 domain 层，持久化只存枚举名）。

## 模块自有持久化（各服务）

- 路径：`<service>/src/main/java/com/payment/<service>/infra/persistence/<module>/`
- 每模块：`<Xxx>Mapper`（extends `BaseMapper`）+ `<Xxx>Repository` 实现（承接 domain 仓储接口，domain 不依赖 MyBatis）。
- 历史/审计类表（如 `payment_attempt`、回调历史）记录每次渠道交互与 UNKNOWN 信息，支撑收敛与对账。

## 幂等（资金入口）

- 支付/退款等资金入口建表带唯一约束列（幂等键），`recordIfAbsent` 语义落到 DB 唯一索引兜底，
  不是仅进程内 `InMemoryIdempotencyRegistry`（那只用于基础测试）。
