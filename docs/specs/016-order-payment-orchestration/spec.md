# Feature Specification: 支付编排职责归位（order-service 升为业务编排者，payment-service 退回能力提供方）

**Feature Branch**: `016-order-payment-orchestration`

**Created**: 2026-09-06

**Status**: Draft（架构决策见 `docs/adr/0025-order-payment-orchestration.md`（ADR-0054，Proposed）；Supersedes ADR-0064 §决策#4）

**Input**: 负责人裁决：「重复支付 / 超额支付的处理归属订单 / 交易编排层，用 `transaction_no + payment_no` 去发起自动退款；支付成功回调通知到 order-service 这层，再由 order-service 去通知履约和权益。order-service 内含 order 层（订单创建 / 商品 / 金额）与 transaction 层（交易动作含重复支付自动退款），order-no 与 transaction-no 一比一；payment 层负责支付流程编排（调用 payment_attempts 各渠道支付 + 记账），transaction-no 与 payment-no 一比多。保留 `fulfillment → entitlement` 链。」

> 本 Feature **不是从零构建**——`payment-service` / `order-service` / `fulfillment-service` / `entitlement-service` 的核心能力均已落地。本 Spec 是**职责迁移 / 领域边界收口型** Spec：把「支付成功后的业务编排权」从 payment-service 移交到 order-service（**transaction 层**负责正常 / surplus 判定与自动退款发起；**order 层**负责订单状态推进、confirmStock 与履约驱动——负责人 2026-09-06 明确），并让自动退款的**决策与发起**归属 order。涉及重大服务边界变化，按 `ai-workflow.md` 先立 ADR-0054，再写本 Spec。

## 当前代码现实（已核实，禁止按绿地项目理解）

**当前是「payment-service 为扇出中心」**，与负责人目标架构相反。四项已核实的真实差距：

| # | 缺口 | 代码证据 | 影响 |
|---|---|---|---|
| G1 | **履约由 payment 直调，order 不是编排者** | `payment-service/.../PaymentResultProcessor.java:73` `fulfillmentGateway.notifyPaymentSucceeded(...)`；`PaymentApplicationService.java:118` 同步 charge 路径同样直调 | 支付成功后的业务流程不受 order 控制 |
| G2 | **自动退款由 payment 自发起（catch 409）** | `PaymentResultProcessor.java:78-82` catch `OrderNotPayableException` → `autoRefundGateway.autoRefund(payment.getPaymentNo(), ex)`；`PaymentAutoRefundService.java:47` `autoRefund(String paymentNo, OrderNotPayableException cause)` | 业务判定（多收）在 order、执行决定在 payment，跨服务异常耦合 |
| G3 | **order.onPaymentSucceeded 不驱动下游** | `order-service/.../OrderApplicationService.java:196-236`：仅 `markPaid(paymentNo)` + `transaction.succeed()` + `confirmStock`，**无** fulfillment / entitlement 触发 | 订单层收口不完整，编排权缺位 |
| G4 | **transaction 层空壳（仅领域状态机）** | order-service 仅有 `OrderApplicationService`，**无** `TransactionApplicationService`；`Transaction` 只是状态机（PENDING→PROCESSING→SUCCEEDED…） | 交易动作（含自动退款）无归属层 |

**另发现（文档漂移，供负责人知悉）**：

| # | 新发现 | 代码 / 文档证据 | 处置 |
|---|---|---|---|
| N1 | **data-model 仍写 Transaction 1:1 Payment** | `docs/specs/001-core-business-model/data-model.md:13`「Payment … MVP 中与 Transaction 1:1」；但 ADR-0064 #2 已改为「一交易多支付单」（`payments` 去掉 `uk_payments_transaction_id` 唯一约束、新增 `attempt_seq`） | 本 Feature 须同步修正为 1:N（FR-011），避免文档继续误导 |
| N2 | **自动退款请求无 transactionNo** | `PaymentAutoRefundService.java:47` 签名仅 `paymentNo`；`RefundAttemptRequest` 仅含 `orderNo/paymentNo` | 与负责人「用 transactionNo + paymentNo 发起」不符，须加字段（FR-005） |
| N3 | **payment→order 通知缺 transactionNo 透传** | `PaymentSucceededRequest` 未携带 `transactionNo`；payment 侧已知其所属 transaction | order 需自行反查；须补全（FR-006） |
| N4 | **退款渠道流水号被丢弃，对账用合成引用** | `PaymentRefundService.java:52-67` 调渠道后 `channelReference` 仅回传不落库（`refunds` 表无该列、无退款渠道尝试记录）；`RefundFactsService.java:33` 对账退款事实用 `"refund-" + r.getId()` 合成引用 | 退款编排三步链（FR-017）：落退款渠道尝试记录，对账退款事实改用真实渠道退款流水号 |

### 能力现状矩阵（诚实标注，禁止按绿地项目理解）

| 能力 | 状态 | 证据 / 说明 |
|---|---|---|
| order 层：创建 / 商品 / 金额 / 订单状态机 | 已实现 | `OrderApplicationService.java:60-130` |
| order ↔ transaction 1:1 建单 | 已实现 | `OrderApplicationService.java:88-92` `new Transaction(order.getOrderNo(), ...)` |
| transaction 领域状态机（PENDING/PROCESSING/SUCCEEDED…） | 已实现（无应用服务承载） | `domain/Transaction.java` |
| payment 流程编排（多渠道 payment_attempts + 记账） | 已实现 | `PaymentApplicationService` / `PaymentResultProcessor` |
| payment 直接扇出履约 | 已实现（**目标：移除**） | `PaymentResultProcessor.java:73`、`PaymentApplicationService.java:118` |
| payment 捕获 409 自行自动退款 | 已实现（**目标：迁移到 order**） | `PaymentResultProcessor.java:78-82`、`PaymentAutoRefundService.java` |
| 履约 → 权益（fulfillment 内 EntitlementGateway） | 已实现（**保留**） | `fulfillment-service/.../application/EntitlementGateway.java:7` |
| order 驱动履约 / 权益 | **缺口 G1/G3** | order 不触发任何下游 |
| order transaction 层决策 + 自动退款发起 | **缺口 G2/G4** | 无 `TransactionApplicationService` |
| 自动退款带 transactionNo | **缺口 N2** | `RefundAttemptRequest` 缺字段 |

## User Scenarios & Testing

> 标注约定：无标记 = 当前已实现；`[目标]` = 本 Feature 建议值；`[待定]` = 留待后续。

### User Story 1 - 支付成功后由 order-service 统一编排履约与权益 (Priority: P1)

作为平台交易编排方，我希望一笔支付成功后，**由 order-service** 统一驱动后续的履约与权益授予（经由既有 `fulfillment → entitlement` 链），使得订单聚合根真正拥有「支付成功后的业务流程」，而不是由 payment-service 越过订单直接扇出，从而让领域边界（order=编排者，payment=能力提供方）成立。

**Why this priority**: 这是负责人目标架构的第一根支柱（G1/G3）。当前 payment 直调履约（`PaymentResultProcessor.java:73` / `PaymentApplicationService.java:118`），order 的 `onPaymentSucceeded` 只改自身状态，编排权缺位。

**Independent Test**: 跑通「下单 → 选渠道支付 → 渠道回调成功 → 断言 order-service 内触发了 `FulfillmentGateway.notifyPaymentSucceeded` 且 fulfillment / entitlement 落库」；同时断言 payment-service 不再持有对 `FulfillmentGateway` 的调用（代码级：移除 `PaymentResultProcessor` 的 `fulfillmentGateway` 字段与 `:73` 调用、`PaymentApplicationService.java:118` 调用）。

**Acceptance Scenarios**:

1. **Given** 一笔支付成功回调到达 payment，**When** payment 处理完成，**Then** payment 编排完成自身支付指令（渠道支付 + 记账 `ledgerGateway.postPaymentCapture` 照常执行），业务侧仅调用 `orderGateway.notifyPaymentSucceeded(...)`（携带 `transactionNo` / `paymentNo` / `orderNo` / `channelReference` / `amountMinor`），**MUST NOT** 直接调用 `FulfillmentGateway` 或 `AutoRefundGateway`（FR-001）。
2. **Given** order 收到支付成功通知且订单尚未 PAID，**When** transaction 层判定「正常到账」并委派 order 层处理，**Then** 由 **order 层**执行 markPaid + transaction.succeed() + confirmStock，并调用 `FulfillmentGateway.notifyPaymentSucceeded(...)` 驱动履约（FR-002/FR-003；confirmStock 与履约驱动属 order 层，不在 transaction 层）。
3. **Given** 履约完成，**When** fulfillment 内 `EntitlementGateway` 触发，**Then** entitlement 正常授予（既有链保留，本 Feature 不改）（FR-008）。
4. **Given** 支付成功，**When** 链路完成，**Then** 对账 / 结算不受影响（其引用仍为渠道流水号 `out_channel_no`）（FR-009 之外的既有契约不变）。

---

### User Story 2 - 重复 / 超额支付由 order transaction 层决策并自动退款 (Priority: P1)

作为平台交易编排方，我希望当同一订单的**第二笔（或更多）支付单**成功回调时，**由 order-service 的 transaction 层**基于「本交易是否已 PAID」的权威状态判定其为「重复 / 超额（surplus）」，并**以 `transactionNo + paymentNo` 经 `PaymentGateway.refund(...)` 自动原路退款**，使得自动退款的**决策与发起**归属业务层、payment 仅作执行，从而消除「跨服务 409 异常耦合」（G2/G4）。

**Why this priority**: 这是目标架构的第二根支柱，也是负责人点名的核心诉求。当前自动退款在 payment 内 catch 409 发起（`PaymentResultProcessor.java:78-82`），业务权威与执行错位。

**Independent Test**: 构造「首笔支付成功 → 订单 PAID + 履约 + 权益」「第二笔同订单支付成功回调 → order transaction 层判定 surplus → 调 `PaymentGateway.refund(transactionNo, paymentNo)` → 退款单 SUCCEEDED」；断言 order **不再抛 409**、payment 不再自发起退款；退款请求 `RefundAttemptRequest` 含 `transactionNo`，幂等键含 `transactionNo`。

**Acceptance Scenarios**:

1. **Given** 订单已被首笔支付置为 PAID，**When** 第二笔支付单成功回调到达 order，**Then** transaction 层判定为 surplus（基于自身 `transaction` 状态，非跨服务异常），**MUST NOT** 抛 `OrderNotPayableException` 给 payment（FR-007）。
2. **Given** 判定 surplus，**When** transaction 层处理，**Then** 以 `transactionNo + paymentNo` 调用 `PaymentGateway.refund(...)` 发起自动退款（FR-004）；退款执行由 payment-service `PaymentRefundService` 完成（能力提供方）。
3. **Given** 自动退款请求，**When** 构造 `RefundAttemptRequest`，**Then** **MUST** 携带 `transactionNo`（与 `paymentNo` 共同定位业务上下文），幂等键 **MUST** 含 `transactionNo`（如 `autorefund:{transactionNo}:{paymentNo}`）（FR-005）。
4. **Given** 自动退款链路，**When** 退款成功，**Then** 行为与原「重复支付→退款」端到端一致（仅发起方由 payment 变为 order），且 `payment_attempts` / `refunds` 落库正确（FR-010）。
5. **Given** 自动退款最终失败，**When** payment 侧重试耗尽，**Then** 转人工 / 对账兜底（沿用 `PaymentAutoRefundService` 既有「最终失败转人工」语义，仅触发方上移至 order）。

---

### User Story 3 - 交易层可观测与审计收口（自动退款上下文完整）(Priority: P3)

作为平台资金风控 / SRE，我希望 order transaction 层的自动退款决策与发起有完整审计上下文（`transactionNo` / `paymentNo` / `orderNo` / `traceId`），且退款动作沿用资金审计纪律，从而满足 Constitution Observability §2「资金动作 MUST 有审计日志」与跨服务 `traceId` 串联。

**Why this priority**: 自动退款属资金动作；把决策上移到 order 后，审计上下文更完整（带 transactionNo）。列为 P3 因正确性（US1/US2）优先于可观测增强。

**Independent Test**: 触发 surplus 自动退款，断言 `FINANCIAL_AUDIT` / 业务日志含 `transactionNo` + `paymentNo` + `orderNo` + `traceId`，且可跨 order / payment 两服务串联。

**Acceptance Scenarios**:

1. **Given** order 发起自动退款，**When** 动作成功，**Then** 审计 / 日志含 `transactionNo` / `paymentNo` / `orderNo` / `traceId`，不记录敏感数据（FR-005 上下文 + Constitution §VII）。
2. **Given** 跨服务 traceId 断裂风险，**When** order→payment 退款调用，**Then** `traceId` MUST 透传（`TraceContext`），保证审计可跨服务串联。

---

### Edge Cases

- **首笔支付失败、换渠道重付成功**：首笔 FAILURE 只更新支付单（ADR-0064 #3），第二笔 SUCCESS → order 正常到账路径（markPaid + 履约 + 权益），不触发 surplus。
- **两笔支付单几乎同时成功（并发）**：order 基于 `transaction` 状态机做权威判定，第二笔进入 surplus → 自动退款；第一笔正常到账。状态迁移由 order 聚合根内同步保证（必要时 version 乐观锁）。
- **surplus 退款幂等**：同一 `(transactionNo, paymentNo)` 重复触发退款 MUST 幂等吸收（幂等键 `autorefund:{transactionNo}:{paymentNo}`）。
- **payment→order 通知失败 / 超时**：沿用现有 RPC 超时 / 重试纪律（Constitution §V.6）；order 未收到则不推进，payment 侧不自行扇出履约（避免双触发）；可由主动查询 / 对账兜底。
- **order→fulfillment 调用失败**：保留当前「catch 吞掉不回滚 + 重试 / 对账兜底」语义（迁移时一致性保持）；不得因迁移引入订单状态回滚（支付已成功，订单应最终 PAID）。
- **order→payment 退款调用失败**：由 payment 侧既有重试（指数退避）+ 最终失败转人工兜底（US2 AC5）。
- **退款域已在 payment-service 内**（ADR-0064 #5）：order 调用的是进程内 `PaymentRefundService` 能力，无额外跨服务跳变。
- **对账 / 结算不受影响**：其引用仍是渠道流水号 `out_channel_no`，本 Feature 不改对账 / 结算契约（FR-009 之外的既有契约）。

## Out of Scope（明确不做）

- **改动 `fulfillment → entitlement` 链**：负责人明确保留（FR-008）。
- **改动记账（ledger postPaymentCapture）归属**：记账属 payment 层**支付指令编排的一部分**（负责人 2026-09-06 明确「payment 层要编排支付指令，包括记账系统」），保留在 payment-service 内，本 Feature 不迁移。
- **transaction 与 payment 的 1:N 建模重建**：已由 ADR-0064 建立，本 Feature 仅明确归属与编排方向，不重做数据模型。
- **数值 ID / 业务单号体系调整**：ADR-0063 已收口，本 Feature 沿用（FR-009）。
- **对账 / 结算链路改造**：其引用仍为渠道流水号，不受影响。
- **权益由 order 直接触发（绕过 fulfillment）**：不采纳；保留 fulfillment→entitlement（与负责人裁决一致）。
- **引入 MQ / 异步事件 / Saga 框架**：本 Feature 用同步 RPC 编排，不引入新中间件（Constitution §IV）。
- **`channel_reference → out_channel_no` 改名**：属独立重构（负责人已单列待办），本 Feature 不混入。

## Requirements

### Functional Requirements

- **FR-001**: payment-service 在支付成功回调中 MUST 编排完成自身支付指令——渠道支付落 `payment_attempts` + **记账**（`ledgerGateway.postPaymentCapture`，属支付指令编排的一部分，保留在 payment 内）；**业务侧扇出** MUST 仅通知 order-service（`orderGateway.notifyPaymentSucceeded`），MUST NOT 直接调用 `FulfillmentGateway` 或 `AutoRefundGateway`；移除 `PaymentResultProcessor.java:73/82` 与 `PaymentApplicationService.java:118` 的扇出（**记账不在移除之列**）。
- **FR-002**: order-service MUST 提供 transaction 层应用服务（`TransactionApplicationService`），在收到支付成功通知时判定「正常到账」或「重复 / 超额（surplus）」。
- **FR-003**: 正常到账时，transaction 层 MUST 将「正常」判定**委派 order 层**执行：order 层 MUST 完成 markPaid + transaction.succeed() + confirmStock 并驱动履约（`FulfillmentGateway.notifyPaymentSucceeded`），经由既有 `fulfillment → entitlement` 链授予权益；MUST NOT 由 payment-service 直接触发，MUST NOT 由 transaction 层直接执行订单状态推进 / confirmStock / 履约驱动（负责人 2026-09-06 明确分工）。
- **FR-004**: surplus 时，order transaction 层 MUST 以 `(transactionNo, paymentNo)` 经 `PaymentGateway.refund(...)` 发起自动退款；退款执行仍由 payment-service 完成（能力提供方），并遵循 **FR-017 退款编排三步链**。
- **FR-005**: 自动退款请求 `RefundAttemptRequest` MUST 携带 `transactionNo`（与 `paymentNo` 共同定位业务上下文）；幂等键 MUST 含 `transactionNo`（如 `autorefund:{transactionNo}:{paymentNo}`）。
- **FR-006**: payment→order 通知 `PaymentSucceededRequest` MUST 携带 `transactionNo`（payment 侧已知其所属 transaction）。
- **FR-007**: order 层与 transaction 层同处 order-service；基数关系：`order_no : transaction_no = 1:1`、`transaction_no : payment_no = 1:N`（沿用 ADR-0064）、**`payment_no : payment_attempts = 1:1`**（每张支付单仅一条渠道尝试记录，渠道重试在同一 attempt 行内 `retry_count` 递增、不新建行）；transaction 层拥有「本交易是否已 PAID」权威状态，surplus 判定不再依赖跨服务 409 异常（order MUST NOT 对 surplus 抛 `OrderNotPayableException`）。
- **FR-008**: 履约 / 权益 / 记账的既有能力（`fulfillment→entitlement`、`ledger postPaymentCapture`）MUST 保持不变；本 Feature 仅迁移「触发职责」，不改动这些能力本身。
- **FR-009**: 所有跨服务调用 MUST 使用业务单号（orderNo / transactionNo / paymentNo / refundNo），不暴露数值 ID（沿用 ADR-0063）。
- **FR-010**: 移除 payment 侧 `AutoRefundGateway` 自发起逻辑后，既有「重复支付 → 退款」端到端行为 MUST 仍可达（仅发起方变更）；相关测试 MUST 从 payment 视角迁至 order 视角（如 `TransactionCallbackConflictTest`）。
- **FR-011**: 实现完成后 MUST 同步修正文档漂移：`docs/specs/001-core-business-model/data-model.md` 的「Transaction 1:1 Payment」更新为 1:N（对齐 ADR-0064 现实）；ADR-0064 #4 标记为被 ADR-0054 supersede；本 spec / plan 与代码对齐。
- **FR-012**: 金额 MUST 一律用最小货币单位 `long` 分或 `BigDecimal`（明确 scale），MUST NOT 使用 `float`/`double`（Constitution §II.1）。
- **FR-013**: 所有跨服务出站 RPC（order→payment 退款、order→fulfillment 履约、payment→order 通知）MUST 显式配置超时（Constitution §V.6）。
- **FR-014**: 数据库-per-service：各服务只读写自有 Schema，MUST NOT 直接 SQL 他服务表（Constitution §IV.4）。
- **FR-015**: 金额路径与状态机路径 MUST 有单元测试与集成测试（正常到账、surplus 退款、并发双成功、退款幂等、迁移后无 409）；MUST NOT 删测试或改测试迎合错误实现（Constitution §VIII.3/4）。
- **FR-016**: 本 Feature MUST NOT 引入 MQ / 分布式事务 / 跨服务异步事件 / 熔断中间件（Constitution §IV、ADR-0001）。
- **FR-017**: **退款编排三步链**（负责人 2026-09-06 明确）：transaction 层发起退款后，payment 退款域 MUST 按三步执行——①调用退款接口**生成退款单号 `refundNo`**（RF+雪花；幂等回查 + intake lock + 防超退校验不变）；②**生成退款渠道尝试记录**（对称支付侧 `payment 1:1 payment_attempts`：复用渠道交互记录 `payment_attempts` 落一条退款尝试，`payment_no` 关联 + `channel_reference` = 渠道退款流水号，唯一约束兜底），修复 N4「渠道退款流水号被丢弃」缺口；③调用外部渠道执行退款（`channel.refund`，三态收敛）。成功后既有后处理编排（履约撤销 → 权益吊销 → 记账冲正）不变；对账退款事实（`RefundFactsService`）MUST 改用真实渠道退款流水号（自退款尝试记录取），**废弃 `"refund-{id}"` 合成引用**（N4）。

### Key Entities

- **OrderApplicationService（order 层，已实现 + 收敛）**：订单创建、SKU / 价格校验、价格快照、订单状态机、金额追踪；持有 `order_no ↔ transaction_no` 1:1；**负责支付成功后的订单侧动作**——markPaid / transaction.succeed() / confirmStock / 履约驱动（由 transaction 层判定「正常到账」后委派）。位置 `order-service/.../application/OrderApplicationService.java`。
- **TransactionApplicationService（transaction 层，新增）**：交易动作编排。接收 `PaymentSucceededRequest(transactionNo, paymentNo, orderNo, ...)`，判定正常 / surplus；正常 → **委派 order 层**执行状态推进与履约驱动（权益经既有 fulfillment 链）；surplus → 发起自动退款 `PaymentGateway.refund(transactionNo, paymentNo)`。**不直接执行 confirmStock / 履约驱动**。位置 `order-service/.../application/TransactionApplicationService.java`（新建）。
- **Transaction（order 领域，已实现 + 升格）**：交易单聚合根，状态机 PENDING→PROCESSING→SUCCEEDED…，持有 `transaction_no` 与 `order_no` 1:1，并通过 `payment_no` 集合表达 1:N（沿用 ADR-0064）。位置 `order-service/.../domain/Transaction.java`。
- **PaymentResultProcessor / PaymentApplicationService（payment 层，改造）**：移除 `fulfillmentGateway` / `autoRefundGateway` 扇出，仅保留 `orderGateway.notifyPaymentSucceeded` + `ledgerGateway.postPaymentCapture`；新增 `PaymentGateway.refund(...)` 作为被 order 调用的退款命令执行入口。位置 `payment-service/.../application/`。
- **PaymentGateway（order→payment 出站端口，新增 / 改造）**：`refund(transactionNo, paymentNo, ...)` 命令；实现经 Feign 指向 payment-service（8084）。位置 order-service `infra/client/` 与 payment-service 对应 controller。
- **FulfillmentGateway（order→fulfillment 出站端口，已实现）**：`notifyPaymentSucceeded(...)`；本 Feature 改为由 order **层**调用（原由 payment 调用）。位置 `order-service/.../application/FulfillmentGateway.java`。
- **EntitlementGateway（fulfillment 内，已实现 + 保留）**：履约完成后触发权益。位置 `fulfillment-service/.../application/EntitlementGateway.java:7`。
- **PaymentAutoRefundService（payment 内，降级为执行器）**：自动退款的业务执行（重试 / 最终失败转人工）保留，但**触发方由 payment 自发起改为 order 调用**；`autoRefund` 签名补 `transactionNo`。位置 `payment-service/.../application/PaymentAutoRefundService.java`。
- **PaymentRefundService（payment 内，改造）**：渠道退款尝试执行器。按 FR-017 三步链，调 `channel.refund` 前**落一条退款渠道尝试记录**（复用 `payment_attempts`，`channel_reference` = 渠道退款流水号），修复 N4。位置 `payment-service/.../application/PaymentRefundService.java`。
- **RefundFactsService（payment 内，修正）**：对账退款事实提供方；`channelReference` 从合成 `"refund-{id}"` 改为**真实渠道退款流水号**（自退款尝试记录取，N4）。位置 `payment-service/.../refund/application/RefundFactsService.java`。
- **RefundAttemptRequest（公共 DTO，改造）**：新增 `transactionNo` 字段。位置 `common/common-dto/.../rpc/RefundAttemptRequest.java`。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 重复支付场景：首笔成功 → 订单 PAID + 履约 + 权益；第二笔同订单成功回调 → order 判定 surplus → 自动退款发起 → 退款单 SUCCEEDED；全程 **0** 人工干预，且 order **不再抛 409**（FR-004/FR-007）。
- **SC-002**: 正常单笔支付：支付成功 → order 驱动履约 + 权益，链路行为与现状一致（FR-003/FR-008）。
- **SC-003**: payment-service **不再持有** `FulfillmentGateway` / `AutoRefundGateway` 依赖（代码级验证：`PaymentResultProcessor` / `PaymentApplicationService` 无对应字段与调用）。
- **SC-004**: 自动退款 `RefundAttemptRequest` **含** `transactionNo`；退款幂等键 **含** `transactionNo`（FR-005）。
- **SC-005**: 对账 / 结算链路不受影响，其引用仍为渠道流水号 `out_channel_no`（FR-009 之外契约不变）。
- **SC-006**: `mvn -o clean verify -fae` 全量通过；相关测试从 payment 视角迁至 order 视角（FR-010/FR-015）；无 `float`/`double` 出现在金额路径。
- **SC-007**: 文档漂移收口：data-model.md Transaction 改 1:N；ADR-0064 #4 标记 superseded by ADR-0054（FR-011）。
- **SC-008**: 退款三步链：surplus 退款后 `payment_attempts` 存在对应退款尝试记录（`channel_reference` = 渠道退款流水号）；对账退款事实的引用为该真实流水号（非 `"refund-{id}"` 合成值）（FR-017 / N4）。

## Assumptions

- `payment-service` / `order-service` / `fulfillment-service` / `entitlement-service` 核心能力已实现，本 Feature 只做职责迁移，不重写既有匹配 / 记账 / 履约 / 权益算法。
- 不引入 MQ / 分布式事务 / 跨服务异步事件 / 熔断中间件；编排为同步 RPC（Constitution §IV、ADR-0001）。
- `fulfillment → entitlement` 链保留，权益触发点不变。
- 记账（ledger）保留在 payment-service，不迁移。
- transaction 与 payment 的 1:N 建模已由 ADR-0064 建立，本 Feature 不重建数据模型（仅补 `transactionNo` 透传与 `RefundAttemptRequest.transactionNo`）。
- 业务单号体系（ADR-0063）与渠道流水号 `out_channel_no`（负责人单列待办）已 / 将独立收口，本 Feature 不混入改名。

## Dependencies（依赖与前置）

| 依赖 | 状态 | 说明 |
|---|---|---|
| ADR-0063（跨系统业务单号） | ✅ Accepted | 本 Feature 跨服务调用沿用业务单号纪律 |
| ADR-0064（一交易多支付单 / 退款域并入 payment-service） | ✅ Accepted（#4 将被本 ADR-0054 supersede） | transaction 1:N payment 建模、退款域在 payment-service 内，是本 Feature 的前提 |
| `payment-service` 退款能力（`PaymentRefundService`） | 已实现 | order 调用的退款命令执行落于此 |
| `fulfillment-service` `EntitlementGateway` | 已实现（保留） | 权益授予链不变 |
| `order-service` `FulfillmentGateway`（→ fulfillment） | 已实现 | 改由 order 层调用 |
| `docs/specs/001-core-business-model/data-model.md` | 需修正 | Transaction 1:1 → 1:N（FR-011 / N1） |
| 现有测试 `PaymentCallbackConflictScenarioTest` | 需迁移 | 从 payment 视角迁至 order 视角（FR-010） |

## Clarifications

### Session 2026-09-06

- **性质**：职责迁移 / 领域边界收口型 Spec，非绿地构建。当前是「payment 为扇出中心」，目标是「order 为业务编排者、payment 退回能力提供方」（见文首差距表 G1~G4）。
- **与 ADR-0064 的关系**：本 Feature **Supersedes ADR-0064 §决策#4**（自动退款由 payment catch 409 自行发起）；ADR-0064 其余条款（一交易多支付单、退款域并入 payment-service、三渠道 mock）**保持不变**。ADR-0054 已注明 Supersedes，ADR-0064 须补反向指针（README 状态机要求双向链接）。
- **负责人已拍板的四点**：
  1. **保留 `fulfillment → entitlement`**：权益仍由 fulfillment 完成后触发，order 只作为编排发起方（FR-008）。
  2. **自动退款用 `transactionNo + paymentNo` 发起**：决策与发起归 order transaction 层，payment 仅执行（FR-004/FR-005）。
  3. **order 层 / transaction 层分工**（2026-09-06 负责人纠正）：`confirmStock` 与履约驱动（`FulfillmentGateway.notifyPaymentSucceeded`）属 **order 层**，**不在 transaction 层**；transaction 层只做「正常 / surplus 判定」与「surplus 时发起自动退款」，正常路径**委派 order 层**执行状态推进与履约驱动（FR-003）。
  4. **退款编排三步链**（2026-09-06 负责人明确）：发起退款时——生成 `refundNo` → 调用 paymentAttempt 机制生成一条退款记录（渠道尝试记录，含渠道退款流水号）→ 调用外部渠道退款（FR-017；顺带修复 N4 对账合成引用缺口）。
- **subtle 点（实现期须注意）**：记账（ledger postPaymentCapture）**保留在 payment-service**（属 payment 层支付指令编排的一部分），不随履约扇出一起迁走——只有 `fulfillmentGateway` 与 `autoRefundGateway` 两个扇出移除，`ledgerGateway` 保留。
- **文档漂移（N1~N3）**：data-model 的 Transaction 1:1 Payment、自动退款缺 transactionNo、payment→order 通知缺 transactionNo，均为真实漂移，已列入 FR-005/FR-006/FR-011，实现时同步收口。
- **测试迁移风险**：`PaymentCallbackConflictScenarioTest` 依赖「payment catch 409 → autoRefund」；重构后这些断言须迁到 order 视角（`TransactionCallbackConflictTest`），禁止删测试迎合实现（FR-015）。
- **链路韧性**：order→fulfillment 调用失败须保留「catch 吞掉不回滚 + 重试 / 对账兜底」语义（与现状一致）；不得因迁移引入订单状态回滚。
