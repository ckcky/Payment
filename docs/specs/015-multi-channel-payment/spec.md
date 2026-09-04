# Spec: 015-multi-channel-payment（一交易多支付单 + 退款并入 + 三渠道 mock + 流量脚本）

**版本**：0.1
**日期**：2026-09-04
**状态**：Accepted（负责人确认，按三次模型反转后的最终口径）
**分支**：`feature/015-multi-channel-payment`

> 本 Spec 是之前「demo 全链路流量 + 换支付方式」需求的最终落地规范，整合了三次模型反转：
> ① 一交易多支付单；② 退款服务并入 payment-service；③ 扣库存由 order-service 发起。
> 两条核心不变量（见 §3）已逐条核对代码现状，是本 Spec 的硬性约束。

## 1. 背景与目标

`011-demo-showcase` 已交付单条 happy-path 的可演示链路，但其模型是「一订单 ↔ 一交易 ↔ 一支付单」，且支付单在**下单时即创建**。这无法满足演示与真实业务的两个关键诉求：

- **同一订单换支付方式**：用户用微信支付失败，想改用支付宝/抖音重新支付这笔订单——当前模型无法表达「同一订单的第二张支付单」，更无法让旧支付单保留失败、新支付单继续推进。
- **多渠道 + 全链路压测流量**：demo 需要一个后台脚本以约 2 TPS 持续发起全链路交易（成功/失败都要有），并覆盖微信/支付宝/抖音三渠道。当前 `MockChannelAdapter` 的 `channelCode` 被硬编码为 `mock`，且只有成功路径可脚本化。

本 Feature 目标：

- 把支付模型升级为「一订单 ↔ 一交易 ↔ **多支付单**」，每选一个渠道建一张支付单；
- 回填真实的多渠道 mock（ALIPAY / WECHAT / DOUYIN / MOCK），收银台支持「换渠道」；
- 把 `refund-service` 能力并入 `payment-service`（重大架构合并，服务数 10→9）；
- 补齐「重复成功 / 已关闭订单回调成功」两类场景的自动退款闭环；
- 提供纯 bash 的 2 TPS 流量脚本，可作为 demo 资产长期复用。

## 2. 现状核实（基于真实代码，2026-09-04 核对）

| # | 约束 | 证据 | 影响 |
|---|---|---|---|
| C1 | `uk_payments_transaction_id` **唯一** | `03-payment-schema.sql:28` | 必须删唯一、改普通索引；否则第二张支付单被 `insertNew` 的 `catch DuplicateKeyException` 静默复用成第一张 |
| C2 | 账本幂等键 = `"PAYMENT:" + idempotencyKey`，而 idempotencyKey = `"payment:" + orderId` **不含 paymentId/attemptSeq** | `FeignLedgerPostingGateway`；`OrderApplicationService`（idempotencyKey 构造处） | 第二笔成功撞 `uk_postings_idempotency_key`，ledger 回查返回首笔 → 静默少记账。必须改 |
| C3 | `orders.payment_id` 是**单值 BIGINT** | `01-order-schema.sql` | 多支付单下定义「主支付单」= 第一张成功的 |
| C4 | refund 前置校验：payment 必须 SUCCEEDED 才能退，否则 REJECTED（200 非 409） | `RefundApplicationService`；`RefundPolicy` | 重复支付必须先置 SUCCEEDED 再退款 |
| C5 | `markPaid` 在订单非 PENDING_PAYMENT 时抛 `STATE_TRANSITION_VIOLATION`，被 payment 侧 `catch (RuntimeException ignored)` **吞掉** | `Order.java`；`PaymentResultProcessor:71-73` | 「已关闭订单回调成功」当前无留痕。必须改成明确 409 |
| C6 | 并发两个 SUCCESS 回调：状态机吸收只覆盖「读时已是终态」，不覆盖「同时读」 | `PaymentResultProcessor`（无事务无锁） | 「第一个为准」不靠状态机保证，靠 order 侧 409 + 自动退款兜底 |
| C7 | 交易单（Transaction）已存在 | `01-order-schema.sql`；`Transaction.java` | 扩展「1:1 → 1:N」，非从零 |
| C8 | 支付侧 30s 未回调转 UNKNOWN，订单 15min 才关 | `TimeoutScanner`；`OrderTimeoutProperties` | 窗口内不冲突 |
| C9 | 库存**无法补货**（`seedStock` 已存在则 return） | `StockApplicationService` | 流量脚本必须自建专用大库存 SKU |
| C10 | demo 环境 Redis 挂会**阻断下单**（`schedule()` 无保护） | `OrderApplicationService` | 顺带加保护（降级记日志，不回滚下单） |

> **关键核定结论（INV-1 已满足）**：`OrderApplicationService.onPaymentSucceeded` 当前**已实现** `catalogClient.confirmStock(...)`——即扣库存**由订单层（order-service）发起**，满足 INV-1 的归属要求。回调通知在 `PaymentResultProcessor.applyAndNotify` 的 SUCCESS 分支，FAILURE 已静默。因此 INV-1 的「订单层扣库存」无需挪动代码，只需保证不破坏现有调用链。

## 3. 两条核心不变量（不可破坏）

- **INV-1（回调成功链路）**：回调成功 → 支付层更新支付单 → **RPC 通知订单层** → 订单层把「订单 PAID + 交易 SUCCEEDED + **确认扣库存**」一次性推进。**扣库存必须由订单层（order-service）发起，不得移到支付层。**
- **INV-2（换渠道）**：用户换支付方式（如微信失败改选抖音）→ 以**同一订单号新建一张支付单**；旧支付单**保留 FAILED 状态、不做任何处理、`不调用 Payment.close()`**。

## 4. 关键用户故事

- **US1 换渠道重付**：用户下单后选微信支付，回调失败（订单仍待支付、交易仍处理中）→ 改选支付宝重新支付同一订单 → 回调成功 → 订单 PAID、交易 SUCCEEDED、库存确认扣减、履约与权益生成、账本平衡。旧微信支付单保留 FAILED。
- **US2 重复成功自动退款**：同一交易两张支付单都回调成功 → 第一张正常 PAID；第二张 SUCCEEDED 后自动退款，账本反向记账平衡，不产生重复履约。
- **US3 已关闭订单自动退款**：订单 15 分钟超时 CANCELLED 后，渠道回调成功 → 自动退款，支付单保持 SUCCEEDED，不产生履约。
- **US4 多渠道 demo 流量**：后台脚本约 2 TPS 持续发起全链路交易，覆盖 ALIPAY/WECHAT/DOUYIN 三渠道与成功/失败/UNKNOWN 路径，可配置时长与成功率，结束有汇总。
- **US5 架构收敛**：`refund-service` 删除，退款能力并入 `payment-service`，对外端点路径与语义不变（端口 8085→8084）。

## 5. 功能需求（FR）

### 5.1 数据模型（一交易多支付单）

- **FR-001** `03-payment-schema.sql`：删除 `uk_payments_transaction_id` 唯一约束，改为普通索引 `idx_payments_transaction_id (transaction_id)`；新增 `attempt_seq INT NOT NULL DEFAULT 1`，加索引 `idx_payments_txn_seq (transaction_id, attempt_seq)`。
- **FR-002** 幂等键改造（修 C2）：支付单 `idempotencyKey = "payment:" + orderId + ":" + channelCode + ":" + attemptSeq`，由 payment-service 在 `insertPending` 按 `count(transaction_id)+1` 计算 `attemptSeq`；账本 `postingKey = "PAYMENT:" + idempotencyKey` 自动含 `attemptSeq`，不再撞键。
- **FR-003** 提供可重放迁移脚本 `015-payment-multi-attempt.sql`（DROP+ADD 索引、ADD 列），对新建库与已初始化库均安全。
- **FR-004** 主支付单语义 = 第一张成功的支付单（`markPaid(paymentId)` 语义不变）。

### 5.2 下单不建单，显式选渠道才建（INV-2 前提）

- **FR-005** `POST :8083/orders`：只建订单 + 交易单 → `confirm()` → PENDING_PAYMENT → 登记 15 分钟超时；响应 `paymentId / paymentStatus / payUrl` 置 `null`。
- **FR-006** 新增 `POST :8083/orders/{orderId}/payments`，body `{"channelCode":"ALIPAY"}`：用**订单自身金额**调 payment-service `POST /payments`；交易单 `start()`（PENDING→PROCESSING，已 PROCESSING 则跳过）；返回 `{paymentId, paymentNo, attemptSeq, channelCode, status, payUrl}`。
- **FR-007** 移除 `OrderApplicationService.doCreateOrder` 中 `paymentGateway.createPayment(...)` 调用。
- **FR-008** `CreatePaymentResponse` 扩 `attemptSeq` / `channelCode`（含兼容构造）。

### 5.3 回调语义（INV-1 / INV-2）

- **FR-009** **SUCCESS** → 支付单 SUCCEEDED → 记账 → RPC 通知 order `on-payment-succeeded`（订单 PAID + 交易 SUCCEEDED + confirmStock，INV-1）。统一在回调路径与同步 charge 路径一致：两路径均触发 `orderGateway.notifyPaymentSucceeded`。
- **FR-010** **FAILURE** → 只更新支付单 FAILED + attempt，**不通知 order**（INV-2：旧单保留 FAILED，不动订单/交易）。
- **FR-011** **UNKNOWN** → 沿用 `markUnknown()`，等 `/payments/{id}/resolve` 收敛。
- **FR-012** 修 C5：order `on-payment-succeeded` 在订单**非 PENDING_PAYMENT**（已 PAID / 已 CANCELLED）时返回**明确 409**（错误码 `ORDER_NOT_PAYABLE`），不再抛 500 被吞。payment 侧 `OrderFeignClient` 识别 409 抛 typed exception（供 P4 捕获触发自动退款）。

### 5.4 自动退款（统一一条规则）

- **FR-013** order 返 409 → payment 捕获 → 触发自动退款，**保留支付单 SUCCEEDED**（C4 账实相符）。
- **FR-014** 内部调用退款（合并后无需 Feign）：同步重试 3 次，指数退避 200ms，仍失败 → 落指标 `payment.auto_refund_failed` + ERROR 日志 → 转人工。
- **FR-015** 幂等键 `"autorefund:" + paymentId`。统一覆盖「重复成功」与「已关闭订单」两场景。

### 5.5 refund-service 并入 payment-service（架构变更）

- **FR-016** `refund-service/**` 代码迁入 `payment-service/.../com/payment/payment/refund/**`（domain/application/infra 分包保留）。
- **FR-017** 端点路径不变（`/internal/refunds` 等），端口 8085 → **8084**。
- **FR-018** payment-service 新增 `EntitlementFeignClient`；删除 `PaymentRefundGateway` + `PaymentRefundFeignClient`，查金额与自动退款改内部调用。
- **FR-019** DDL：`refunds` / `refund_items` / `refund_intake_locks` / `refund_post_process_attempts` 4 表从 `refund` 库迁到 `payment` 库。
- **FR-020** 服务边界：删 `refund-service/` 模块 + 根 `pom.xml` `<module>`；`ServiceBoundaryTest.SERVICES` 移除 `"refund"`（10→9）；端口 8085 释放。
- **FR-021** 脚本与文档一并改：docker-compose / start-all / stop-all / prometheus / demo（`lib.sh` `REFUND_URL`→8084）/ start-demo / mock-channel-web application.yml；ADR-0001/0006 / project-structure / systems / constitution / roadmap / runbook / deployment README；005-refund spec 补归属变更说明。

### 5.6 三渠道 mock

- **FR-022** 渠道码保持 **String**（`ALIPAY` / `WECHAT` / `DOUYIN` / `MOCK`，保留 MOCK 兼容现有测试），不改成枚举。
- **FR-023** `MockChannelAdapter.charge()` 按 `channelCode` 生成带前缀渠道引用（`alipay-ref-` / `wechat-ref-` / `douyin-ref-`，未知回落 `mock-ref-` 并 warn）。不改构造签名，保证既有测试零改动。不引入 `Map<ChannelCode, PaymentChannel>` 注册表。

### 5.7 demo 流量脚本

- **FR-024** `lib.sh` 新增零 fork 函数 `httpq`（一次 curl 拿 status+body）、`jnum` / `jstr`（sed 提取）。
- **FR-025** 新增 `deployment/demo/traffic-gen.sh`：① `POST :8083/orders`（Idempotency-Key）② `POST :8083/orders/{id}/payments`（选渠道）③ `POST :8091/mock-channel/callback`（成败按概率；失败则换渠道回到②，不 sleep）④ 5% UNKNOWN → 延迟 2s → `POST :8084/payments/{id}/resolve`（X-Admin-Token，裁定 FAILURE）→ 换渠道再付。
- **FR-026** 控频：补偿式 `sleep(max(0, start + n*500ms - now))`（默认 2 TPS）。启动自建专用 SKU（`TRAFFIC-SKU-<ts>`，库存 5,000,000）绕开 C9。可配 `--tps / --duration / --success-rate / --unknown-rate / --sku-stock`；默认 2 TPS、成功率 0.70、微信失败偏置、UNKNOWN 0.05。
- **FR-027** `trap` 优雅退出 + JSONL 落 `deployment/logs/traffic-<ts>.jsonl` + 每 10s 滚动统计；配套 `stop-traffic.sh`。
- **FR-028** 收银台 `cashier.html`：渠道展示 +「换用支付宝/抖音支付」按钮 → 调 `POST :8083/orders/{id}/payments`；修 `demo-ref-`+`Date.now()` 同毫秒撞 `uk_attempts_channel_reference`（改 `crypto.randomUUID()`）。

### 5.8 Redis 降级（顺带，C10）

- **FR-029** `OrderTimeoutScheduler.schedule()` 在 Redis 不可用时降级记日志、不阻断下单。

## 6. 验收标准（SC）

- **SC-001** `mvn -o clean verify -fae` 全绿（含 `architecture-tests` 边界门禁，服务数 10 → 9）。
- **SC-002（主链路 INV-1/INV-2）**：下单 → 选微信 → 回调失败（订单仍 PENDING_PAYMENT、交易仍 PROCESSING）→ 换支付宝（INV-2：新支付单、旧单 FAILED）→ 回调成功 → 订单 PAID、交易 SUCCEEDED、库存确认扣减（INV-1，订单层发起）、fulfillment/entitlement 生成、ledger 平衡。
- **SC-003（重复成功自动退款，FR-013）**：同交易两张支付单都回调成功 → 第一张正常 PAID；第二张 SUCCEEDED 后自动退款，`refunds` 表产生 SUCCEEDED 退款，账本反向记账平衡。
- **SC-004（已关闭订单自动退款，FR-013）**：订单 15 分钟超时 CANCELLED 后回调成功 → 自动退款，支付单保持 SUCCEEDED。
- **SC-005** 旧场景脚本（refund / reconciliation）在 8084 上跑通（refund-service 已并入）。
- **SC-006** `traffic-gen.sh` 实测 ~2 TPS，成功率接近配置值，UNKNOWN 全部收敛，10 分钟无 5xx、无库存耗尽中断。
- **SC-007** `bash -n deployment/demo/traffic-gen.sh` 与 `bash -n deployment/demo/stop-traffic.sh` 语法检查通过。

## 7. 已知限制（诚实标注）

| # | 限制 | 影响 | 记录位置 |
|---|---|---|---|
| L1 | `attemptSeq` 计算与插入非原子（并发同 seq 靠唯一约束 + 回查兜底） | 极高并发下偶发回查重试，不影响正确性 | 本 Spec §7；ADR TODO |
| L2 | 不做自动退款的定时扫描兜底（同步重试 3 次后转人工） | 退款渠道持续故障需人工介入 | ADR TODO |
| L3 | 不修改 `/internal/stock/seed` 支持补货（流量自建 SKU 绕开） | 流量脚本需自建大库存 SKU | 见 §5.7 |
| L4 | 不引入 CLOSED 状态；15 分钟到期沿用 `cancel()` → CANCELLED | 到期只看订单是否待支付 | ADR TODO |
| L5 | `uk_payments_transaction_id` 去唯一后，一交易多支付单靠 `attempt_seq` 区分；账务/对账抽取按 `transaction_id` 聚合需感知多支付单 | 对账脚本需适配 | ADR TODO |

## 8. 不做（Out of Scope）

- ❌ 不做 `Map<ChannelCode, PaymentChannel>` 注册表；不改 `channelCode` 为枚举。
- ❌ 不做自动退款的定时扫描兜底（同步重试 3 次后转人工）。
- ❌ 不修改 `/internal/stock/seed` 支持补货（流量自建 SKU 绕开）。
- ❌ 不引入 CLOSED 状态；15 分钟到期沿用 `cancel()` → CANCELLED。
- ❌ 不引入 MQ（Constitution 禁止，同步 RPC 面）。
- ❌ 不改动 INV-1 的扣库存归属（已是订单层发起）。
