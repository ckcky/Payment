# Tasks: 016-order-payment-orchestration

> 承载 ADR-0065（支付编排职责归位）：order-service 升为业务编排者，payment-service 退回能力提供方。
> 每个任务完成后跑对应模块 `./mvnw test` 门禁，最后统一全量门禁。本文档为落地后收口登记。

## T1 契约前置 + 退款三步链落地
- [x] `common-dto`：`PaymentSucceededRequest` 字段 `transactionId` → `transactionNo`（语义对齐 ADR-0062 业务单号口径）
- [x] `common-dto`：`RefundAttemptRequest` 增 `transactionNo`；新增 `RefundCommandRequest` / `RefundCommandResponse`（order → payment 退款命令契约）
- [x] order 侧 `PaymentGateway` 端口增 `refund(RefundCommandRequest)`；`PaymentFeignClient` 对接 `POST /internal/payments/refund-command`
- [x] payment 暴露退款命令入口 `PaymentRefundCommandController`（`/internal/payments/refund-command`，复用退款域三步链）
- [x] `PaymentAttempt` 领域增 `attemptType`（PAYMENT/REFUND）+ `PaymentAttempt.refundAttempt(...)` 工厂；实体/仓储/H2 schema/迁移脚本 `016-refund-channel-attempt.sql` 同步
- [x] `PaymentRefundService` 落退款渠道尝试记录（复用 `payment_attempts`，`channel_reference=渠道退款流水号`，`DuplicateKeyException` 幂等吸收）
- [x] `RefundFactsService` 对账退款事实改取真实渠道退款流水号（无尝试记录的存量回退 `refund-{id}` 合成引用）——修 ADR-0065 背景表 N4 对账缺口

## T2 order transaction 层 + 委派改造
- [x] 新增 `order-service/application/TransactionApplicationService`：接收 `PaymentSucceededRequest`，判定正常 / surplus（已取消订单同 surplus）；正常 → 委派 `OrderApplicationService`；surplus → 以 `transactionNo + paymentNo` 调 `PaymentGateway.refund`（幂等键 `autorefund:{transactionNo}:{paymentNo}`）
- [x] `OrderApplicationService.onPaymentSucceeded` 承接委派：markPaid + transaction.succeed() + confirmStock + **驱动履约**（新增 order 层 `FulfillmentGateway` 端口 + `FulfillmentFeignClient`，plan 中"已实现"标注有误，实际新建）
- [x] `OrderPaymentRpcController` 支付成功入口改走 transaction 层；order 不再返回 409 `ORDER_NOT_PAYABLE`

## T3 payment 瘦身去扇出
- [x] `PaymentResultProcessor` 移除 fulfillmentGateway / autoRefundGateway 直调（仅保留 orderGateway + ledgerGateway）
- [x] `PaymentApplicationService` 同步 charge 路径去履约直调，改通知 order（构造器移除 FulfillmentGateway、注入 OrderGateway）
- [x] 删除 `FulfillmentGateway` / `AutoRefundGateway` / `OrderNotPayableException` / `ResilientFulfillmentGateway` / `FulfillmentFeignClient` / `FeignOrderGateway` 及其测试
- [x] `PaymentClientConfig` 装配清理；`OrderFeignClient` 去 `primary=false`（直接作 `OrderGateway` 注入）

## T4 测试迁移 + 全量验证
- [x] `PaymentCallbackConflictScenarioTest`（payment 视角）迁为 `order-service/.../TransactionCallbackConflictTest`：重复回调幂等吸收 / surplus 判定退款（断言 `transactionNo + paymentNo`）/ 已取消订单退款，全程 0 次 409
- [x] 新增 `PaymentRefundServiceTest` 退款尝试落库断言；`RefundFactsServiceTest` 真实渠道流水号用例
- [x] `PaymentTestStack` 去 RecordingFulfillmentGateway；`PaymentResultProcessor` 相关测试（TerminalConflict / Metrics / ReliabilityMetrics / ChannelQuery / Retry / LedgerPosting / ApplicationService / UnknownResolution / DeferredChannel / CallbackContract）全部迁移
- [x] order 侧 `OrderApplicationServiceTest` / `SuccessfulPurchaseScenarioTest` 补 RecordingFulfillmentGateway 桩
- [x] 全量 `./mvnw test` 绿（174+ 用例 0 失败）

## T5 文档收口
- [x] `data-model.md`：PaymentAttempt 基数 1:1 → 1:N（`attempt_type` 区分 PAYMENT/REFUND）
- [x] ADR-0064（`0024`）§决策#4 标注 **Superseded by ADR-0065**
- [x] `adr/README.md` 索引：ADR-0065 Proposed → **Accepted**
- [x] ADR-0065（`0025`）状态 Proposed → **Accepted**
- [x] 全量门禁通过后统一提交
