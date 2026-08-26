# MVP 代码 Review 发现记录（T078）

> 由 `/review`（通用）与 `/payment-review`（资金专项）两轮只读审查产出，对照 [constitution](../../../.specify/memory/constitution.md) 与 [engineering-standards](../../guides/engineering-standards.md)。
> 本文记录发现，不改动代码；标注「需人类确认」的项按宪法 §8 暂停等拍板。

## 结论速览

资金正确性红线（金额类型 / 跨服务直改 / 散落改状态 / 删测试 / 2PC-XA / 渠道边界）**全部通过**；但存在 2 个高危资金缺陷、1 个状态机接线缺口、若干中低危问题。

## 高危（需人类确认）

### H1 退款超额退款竞态（check-then-act，无锁无事务）
- 位置：`refund-service/.../RefundApplicationService.java:77-106`
- 缺陷：可退款额度校验是「读累计 → 判断 → 落库」的普通无锁序列；同一支付两笔**不同幂等键**的并发退款会读到同一旧累计、双双通过额度判断并触发渠道退款。DB 唯一约束只兜「同键」不兜「不同键超额」；且 `REQUESTED` 不计入累计，窗口进一步放大。
- 影响：**多退**（超额退款），资金不平。
- 方案：A 按 `paymentId` 悲观锁串行化退款受理；B 条件原子 INSERT/计数器预留；C 单本地事务内完成读+写。

### H2 幂等登记仅内存、非持久化
- 位置：`common-core/.../config/CommonCoreAutoConfiguration.java:42`、`common-core/.../idempotency/InMemoryIdempotencyRegistry.java`；各服务 `save → recordIfAbsent` 顺序
- 缺陷：幂等登记唯一实现为内存 Map，重启即失；`save` 先于 `recordIfAbsent`，重启/并发重复时先撞 DB 唯一约束抛 `DuplicateKeyException`，落到全局兜底返回 500，而非「返回首次结果」；`PaymentRepository` 无 `findByIdempotencyKey` 回查。
- 影响：不会重复入账（DB 唯一约束兜住），但「幂等返回原结果」契约被破坏，调用方误判失败。
- 方案：A 以 DB 唯一约束为主 + 冲突捕获回查返回原结果；B 独立持久化幂等表。

### H3 全库无 `@Transactional`（资金多步写非原子）
- 位置：全库 main 代码 `@Transactional` 零命中；典型 `payment/.../PaymentApplicationService.java:56-92`
- 缺陷：createPayment/createRefund/createBatch 均为多步写（save payment → attempt → channel → save×2）却无本地事务边界，中途异常留中间态。
- 影响：部分写入/孤儿行，放大 H1/H2 竞态面。
- 方案：application 层编排方法加 `@Transactional`（仅覆盖单服务本地事务，外部 RPC 放事务外）。

### H4 Order/Transaction 状态机在真实链路从未被驱动
- 位置：`order/.../domain/Order.java`（markPaid/recordRefund/markFulfilling/complete）、`Transaction.java`（start/succeed/fail）在 main 代码零调用
- 缺陷：支付成功链路是 payment→fulfillment→entitlement，**无任何 RPC 回调 order-service** 驱动 markPaid/recordRefund/Transaction；`paid_minor`/`refunded_minor` 恒为 0，Order 恒 `PENDING_PAYMENT`、Transaction 恒 `PENDING`。
- 影响：订单级资金状态与支付/退款事实脱节（不影响结算金额，但订单跟踪形同虚设）。
- 方案：A 补 payment/refund 成功 → order 回写 RPC；B 明确记录「MVP 订单金额字段仅建模、不在链路驱动」的豁免。

## 中危

- M1 对账静默丢弃「无渠道引用」平台事实（`reconciliation/.../ReconciliationMatching.java:56`），漏报 PLATFORM_ONLY 差异。
- M2 退款 UNKNOWN→SUCCEEDED 收敛不触发权益撤销（`refund/.../RefundRpcCallbackService.java:24-36`）。
- M3 结算硬编码 `CNY` 且不按商户过滤（`settlement/.../SettlementApplicationService.java:75-89`）；对账摘要无商户维度。
- M4 `Money` 值对象已实现但领域层零使用，全裸 `long amountMinor` + `String currencyCode`。

## 低危

- L1 渠道 `charge`/`refund` 无异常兜底映射 UNKNOWN。
- L2 渠道 code 硬编码 `"mock"`（`PaymentRefundService`、`OrderApplicationService`）。
- L3 对账匹配忽略 `currencyCode`。
- L4 `PaymentCallbackService` 生产无入站回调端点（Mock 渠道同步返回，属死代码）。
- L5 无 Bean Validation/鉴权（`@Valid` 零命中）。
- L6 `RefundController` 直接暴露 application 命令 + 领域 VO（DTO/Entity 未分离）。

## 未发现（红线确认）

- 金额类型：无 `float`/`double`，统一 `long`/`BIGINT`，`Money` 用 `Math.addExact`/`RoundingMode.UNNECESSARY`。
- 跨服务直改/跨 Schema：无；`@TableName` 均为本服务表，跨服务走 Feign RPC。
- 散落直改 `status`：无；状态迁移集中在 domain 状态机，`Entity.setStatus` 仅持久层 PO。
- 删测试/改测试迎合实现：无。
- 2PC/XA：无；跨服务同步 RPC + 幂等 + 乐观锁。
- Payment ≠ Channel：无；Payment 只依赖 `PaymentChannel` 端口，Mock 在 infra。

## 需人类拍板（§8 / 边界）

1. **H1/H2/H3（幂等持久化 + 退款并发 + 本地事务）**：资金入口幂等强度与一致性策略，需确认是否在本次 MVP 内修复及方案选择。
2. **H4（订单/交易状态机接线）**：是否补跨服务回写 RPC，或明确 MVP 豁免。
