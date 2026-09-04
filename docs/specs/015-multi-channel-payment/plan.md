# Plan: 015-multi-channel-payment（技术方案）

**分支**：`feature/015-multi-channel-payment`
**对应 Spec**：`docs/specs/015-multi-channel-payment/spec.md`
**开发流程**：spec → plan → tasks → 分阶段实现（每阶段 `mvn` 门禁）→ acceptance → 验收报告

## 1. 设计总览

| 维度 | 现状 | 目标 |
|---|---|---|
| 支付模型 | 一订单 ↔ 一交易 ↔ 一支付单（下单即建） | 一订单 ↔ 一交易 ↔ **多支付单**（显式选渠道才建） |
| 扣库存归属 | order-service（`confirmStock`） | 不变（INV-1，已满足） |
| 退款架构 | 独立 `refund-service`（端口 8085） | 并入 `payment-service`（端口 8084），服务数 10→9 |
| 渠道 | `channelCode` 硬编码 `mock` | ALIPAY / WECHAT / DOUYIN / MOCK |
| 流量 | 仅 happy-path 单场景脚本 | 纯 bash 2 TPS 全链路流量脚本 |

## 2. 分阶段实现（每阶段带 `mvn` 门禁）

### P0 数据底座（FR-001~004）
- `03-payment-schema.sql`：去 `uk_payments_transaction_id` 唯一 → `idx_payments_transaction_id`；新增 `attempt_seq` + `idx_payments_txn_seq`。
- 幂等键改造（修 C2）：`idempotencyKey = "payment:" + orderId + ":" + channelCode + ":" + attemptSeq`，`attemptSeq` 由 `insertPending` 按 `count(transaction_id)+1` 计算。
- 领域层：`Payment` 增 `attemptSeq`；`PaymentEntity` 增列；`PaymentRepository` 增 `countByTransactionId`；`MybatisPaymentRepository` 实现 + 映射。
- 迁移脚本 `015-payment-multi-attempt.sql`（可重放）。

### P1 一交易多支付单（FR-005~008，INV-2 前提）
- `POST :8083/orders` 移除 `paymentGateway.createPayment(...)`；响应 `paymentId/paymentStatus/payUrl` 置 null。
- 新增 `POST :8083/orders/{orderId}/payments`：用订单金额调 `payment-service POST /payments`；交易单 `start()`。
- `PaymentPersistence.insertPending` 按 `count(transaction_id)+1` 算 `attemptSeq`；`CreatePaymentResponse` 扩 `attemptSeq`/`channelCode`。

### P2 回调语义（FR-009~012，INV-1/INV-2）
- `PaymentResultProcessor.applyAndNotify`：SUCCESS→更新支付单+记账+RPC通知order；FAILURE→只更新支付单FAILED+attempt不通知order；UNKNOWN→markUnknown。
- order `onPaymentSucceeded` 非 PENDING_PAYMENT 返明确 **409** `ORDER_NOT_PAYABLE`（修 C5）；`OrderFeignClient` 识别 409 抛 typed exception。

### P3 退款合并（FR-016~021，架构变更）
- `refund-service/**` → `payment-service/.../com/payment/payment/refund/**`。
- 端口 8085→8084；新增 `EntitlementFeignClient`；删 `PaymentRefundGateway`+`PaymentRefundFeignClient` 改内部调用。
- 4 张退款表迁 `payment` 库；删模块 + 根 pom module；`ServiceBoundaryTest.SERVICES` 移除 `"refund"`（10→9）。
- 同步 docker-compose / start-all / stop-all / prometheus / demo / mock-channel-web yml / ADR-0001/0006 / project-structure / systems / constitution / roadmap / runbook / README。

### P4 自动退款（FR-013~015）
- 捕获 order 409 → 触发自动退款，保留支付单 SUCCEEDED（C4 账实相符）。
- 内部调用合并后的退款能力，幂等键 `"autorefund:" + paymentId`；同步重试 3 次指数退避 200ms，仍失败 → 指标 `payment.auto_refund_failed` + ERROR 日志转人工。
- 统一覆盖「重复成功」+「已关闭订单」两场景。

### P5 三渠道 mock + 收银台（FR-022~023, FR-028）
- `channelCode` 保持 String；`MockChannelAdapter.charge()` 按 channelCode 生成带前缀引用（`alipay-ref-`/`wechat-ref-`/`douyin-ref-`，未知回落 `mock-ref-`）；不改构造签名。
- `cashier.html` 渠道展示 + 换渠道按钮；`demo-ref-`+`Date.now()` 撞键改 `crypto.randomUUID()`。

### P6 流量脚本（FR-024~027）
- `lib.sh` 零 fork：`httpq`/`jnum`/`jstr`（sed 提取）。
- `traffic-gen.sh`：下单→选渠道→callback（成败按概率，失败换渠道不 sleep）→5% UNKNOWN 延迟 2s→resolve 裁定 FAILURE→换渠道再付。
- 补偿式 sleep 控频（默认 2 TPS）；自建大库存 SKU 绕开 C9；可配 tps/duration/success-rate/unknown-rate/sku-stock；`trap` 优雅退出 + JSONL 汇总；配套 `stop-traffic.sh`。

### P7 文档收口 + 全量门禁（FR 收尾）
- ADR 决策文档合并 5~6 条（超前偏离 / 架构合并 / 多支付单 / 自动退款）。
- roadmap / runbook / README / constitution / project-structure / systems 同步。
- `mvn -o clean verify -fae` 全量门禁（含 architecture-tests 服务数 10→9）。

## 3. 关键文件清单

**DDL**：`deployment/schema/03-payment-schema.sql`、`015-payment-multi-attempt.sql`、退款表 DDL 迁移。

**payment-service**：`application/PaymentPersistence.java`、`application/PaymentResultProcessor.java`、`application/PaymentApplicationService.java`、`infra/client/OrderFeignClient.java`、`refund/**`（迁入）、`infra/client/EntitlementFeignClient.java`（新）、`infra/channel/MockChannelAdapter.java`、`domain/Payment.java`、`infra/persistence/payment/PaymentEntity.java`、`domain/PaymentRepository.java`、`MybatisPaymentRepository.java`。

**order-service**：`api/OrderController.java`、`application/OrderApplicationService.java`、`api/OrderPaymentRpcController.java`、`application/OrderTimeoutScheduler.java`、`api/dto/CreateOrderRequest.java`（可选 channelCode）、`application/ConfirmStockCommand.java`（不变，确认扣库存仍在此）。

**common / 演示**：`common-dto/.../CreatePaymentResponse.java`（+attemptSeq/channelCode）、`mock-channel-web/.../ChannelCallbackProxy.java`、`static/cashier.html`、`deployment/demo/lib.sh`、`traffic-gen.sh`（新）、`stop-traffic.sh`（新）。

**服务边界与脚本**：根 `pom.xml`、`ServiceBoundaryTest.java`、`docker-compose.yml`、`start-all.sh`、`stop-all.sh`、`prometheus.yml`、`demo-monitor-stress.sh`、`demo/start-demo.sh`、`mock-channel-web/application.yml`。

**文档**：`docs/adr/0001`、`0006`、`project-structure.md`、`systems/*.md`、`.specify/memory/constitution.md`、`roadmap.md`、`runbook.md`、`deployment/README.md`，新增 `docs/specs/015-multi-channel-payment/{spec,plan,tasks,acceptance}.md`。

## 4. 风险与对策

| 风险 | 对策 |
|---|---|
| `attemptSeq` 计算与插入非原子 | 并发同 seq 靠唯一约束 + 回查兜底（L1） |
| 合并 refund-service 改动面大、易漏同步 | 一次合并后全量 `mvn` 门禁 + 逐文件核对脚本/ADR |
| 流量脚本撞 `uk_attempts_channel_reference` | `crypto.randomUUID()` 替代 `Date.now()` |
| 与其他任务共享工作树 | 本分支独立；最后只精确 add/commit 015 文件，绝不碰其他任务 staged 的 772 文件 |

## 5. 不做项（写进 ADR TODO）

- 不做 `Map<ChannelCode, PaymentChannel>` 注册表；不改 `channelCode` 为枚举。
- 不做自动退款定时扫描兜底（同步重试 3 次后转人工）。
- 不修改 `/internal/stock/seed` 支持补货（流量自建 SKU 绕开）。
- 不引入 CLOSED 状态；15 分钟到期沿用 `cancel()` → CANCELLED。
- 长跑期间不触发对账；对账演示仍走既有 `scenario-reconciliation.sh`。
