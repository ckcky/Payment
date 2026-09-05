---
description: "支付成功回写订单与交易状态 — 任务清单（补齐规划产物）"
---

# Tasks: 支付成功回写订单与交易状态

**Input**: `docs/specs/002-payment-order-callback/` 下的 plan.md、spec.md、checklists/requirements.md

**前置条件**: `001-core-business-model` 已验收通过；payment-service 与 order-service 的服务边界、幂等、状态机与同步 RPC 基座已具备。本 Feature 的实现已在 001 主线内落地，本清单用于**补齐规划产物**：已落地的任务标 `[X]`，关闭本 Feature 所需的验证 / 补测任务标 `[ ]`。

**组织方式**: 按 002 spec 的三个用户故事（US1 同步推进 / US2 重复幂等 / US3 仅成功回写）组织；跨服务统一同步 RPC（payment → order）。

## 架构基线（沿用 001，单向同步 RPC）

> payment-service 仅在「支付真正迁移为成功」时，通过 `OrderGateway`（Feign 实现 `OrderFeignClient`）调用 `order-service` 的 `POST /internal/orders/on-payment-succeeded`；order-service 按自身状态机推进 `Order` 与 `Transaction`。无新增服务、无新增表、无 MQ、无 Ledger。

## Phase 1: Setup（项目初始化）

> 沿用 001，本 Feature 不新增任何 Setup 任务。

- [X] T001 服务边界与端口约定（payment 8084、order 8083）已就绪，无需变更。
- [X] T002 Maven 父工程与公共模块（`common-core` / `common-dto` / `common-mybatis`）已就绪，`mvnw verify` 可用。

**检查点**: 无需新增基础设施；可直接进入 Foundational 与用户故事。

## Phase 2: Foundational（基础能力）

> 幂等、状态机、同步 RPC 基座在 001 已建立，本 Feature 复用。

- [X] T003 [P] `common-core` 的 `Money` / 幂等基元 / RPC 元数据已具备。
- [X] T004 [P] 各服务状态机（Order / Transaction / Payment）集中转换函数已具备。
- [X] T005 [P] `OrderGateway` 出站 RPC 端口与 `OrderFeignClient` 实现已具备（payment → order）。
- [X] T006 [P] 结构化审计日志（`FINANCIAL_AUDIT`）与业务指标基座已具备。

**检查点**: 基础能力已满足本 Feature 的回写、幂等与可观测需求。

## Phase 3: User Story 1 — 支付成功后订单与交易同步推进（Priority: P1）🎯 MVP

**目标**: 支付明确成功时，订单 `PENDING_PAYMENT → PAID`（整单支付、记录 paymentId），交易 `PROCESSING → SUCCEEDED`，由各自领域状态机驱动。

**独立验收**: 令一笔支付明确成功，验证订单状态为 PAID、已支付金额等于订单金额、交易状态为 SUCCEEDED，且二者均由各自领域规则驱动。

### US1 测试任务（已实现）

- [X] T007 [P] [US1] `order-service/.../domain/OrderStateMachineTest.markPaid` — `PENDING_PAYMENT→PAID` 转换与幂等（`markPaid` 重复返回 false）。
- [X] T008 [P] [US1] `order-service/.../domain/OrderInvariantTest` — 整单支付 `paidMinor = totalMinor`。
- [X] T009 [P] [US1] `order-service/.../scenario/SuccessfulPurchaseScenarioTest` — 订单侧端到端编排（含 Transaction PROCESSING）。

### US1 实现任务（已实现）

- [X] T010 [P] [US1] `order-service/.../domain/Order.markPaid(paymentId)` — `PENDING_PAYMENT→PAID`，`paidMinor=totalMinor`，记录 paymentId，幂等返回 changed；非法前态抛 `STATE_TRANSITION_VIOLATION`。
- [X] T011 [US1] `order-service/.../application/OrderApplicationService.onPaymentSucceeded` — `@Transactional`：加载 Order → `markPaid` → 加载 Transaction → `start()`(若 PENDING) → `succeed()`。依赖 T010。
- [X] T012 [US1] `order-service/.../api/OrderPaymentRpcController` — `POST /internal/orders/on-payment-succeeded`。依赖 T011。
- [X] T013 [US1] `payment-service/.../application/PaymentResultProcessor.applyAndNotify` — `changed && SUCCESS` 时触发 `orderGateway.notifyPaymentSucceeded` 与 `fulfillmentGateway.notifyPaymentSucceeded`；RPC 失败 catch 不回滚支付成功。依赖 T011、T012。
- [X] T014 [P] [US1] `payment-service/.../application/PaymentResultApplier.apply` — 状态机 apply，返回 `changed`；SUCCESS 分支构造 `PaymentSucceededRequest`。
- [X] T015 [P] [US1] `payment-service/.../application/OrderGateway` + `infra/client/OrderFeignClient` — 出站 RPC 端口与 Feign 实现（`@FeignClient("order-service")` → `POST /internal/orders/on-payment-succeeded`）。

**检查点**: 支付成功可驱动订单 PAID + 交易 SUCCEEDED；二者由各自状态机推进，payment 不直接改 order 数据。

## Phase 4: User Story 2 — 重复回调幂等且不重复累加（Priority: P2）

**目标**: 同一笔支付的成功事实重复 / 乱序 / 延迟到达，订单与交易只推进一次，返回当前状态，不二次累加、不重复业务动作。

**独立验收**: 订单已因支付 A 进入 PAID 后，重复发送支付 A 的成功事实，订单状态 / 已支付金额 / 交易状态不变，下游动作只发生一次。

### US2 测试任务（已实现）

- [X] T016 [P] [US2] `payment-service/.../contract/PaymentCallbackContractTest.duplicateCallbackDoesNotPublishTwice` — 重复成功回调不发布第二次履约 RPC（等价于订单回写同逻辑）。
- [X] T017 [P] [US2] `payment-service/.../application/PaymentMetricsTest` — `payment.duplicate_callback` 计数恰好一次。

### US2 实现任务（已实现）

- [X] T018 [US2] `PaymentResultProcessor.applyAndNotify` 的 `changed` 标志 + `Order.markPaid` 幂等返回 false，保证重复成功回调被吸收、`payment.duplicate_callback` 计数。依赖 T010、T013、T014。

**检查点**: 重复 / 乱序 / 延迟回调被幂等吸收，订单与交易不二次推进。

## Phase 5: User Story 3 — 仅明确成功才回写，失败 / 未知不回写（Priority: P3）

**目标**: 只有支付明确成功才触发订单 / 交易成功推进；FAILURE / UNKNOWN 绝不触发成功回写，订单 / 交易停留在可收敛状态。

**独立验收**: 令支付进入 FAILURE 与 UNKNOWN，验证订单保持 PENDING_PAYMENT、交易保持 PROCESSING/UNKNOWN；UNKNOWN 不触发成功回写。

### US3 测试任务（已实现）

- [X] T019 [P] [US3] `payment-service/.../contract/PaymentCallbackContractTest.lateFailureCallbackDoesNotOverwriteSuccess` — 终态成功后迟到失败回调不覆盖。
- [X] T020 [P] [US3] `payment-service/.../contract/PaymentCallbackContractTest.unknownCallbackAfterUnknownStaysUnknownWithoutNewEvent` — UNKNOWN 后再次 UNKNOWN 回调不触发新事件。

### US3 实现任务（已实现）

- [X] T021 [US3] `PaymentResultProcessor.applyAndNotify` 的 `changed && result.status()==SUCCESS` 双控：FAILURE / UNKNOWN 不触发 order / fulfillment RPC。依赖 T013、T014。

**检查点**: 失败 / 未知不触发成功回写；订单 / 交易保持可收敛状态，不猜成败。

## Phase 6: Polish & Closing（关闭本 Feature 所需补测与验证）

**目标**: 补齐应用层单测与 OrderGateway 断言缺口，落实 FR-009 异常留痕，在本地 MySQL 实跑 quickstart 并产出验收，完成 review 与 Roadmap 更新。

- [x] T022 [P] 新增 `OrderApplicationServiceTest.onPaymentSucceeded` 单测：`markPaid` + `Transaction.succeed` 后断言 `order.status==PAID`、`order.paidMinor==totalMinor`、`transaction.status==SUCCEEDED`；非法前态（`CANCELLED`/`CLOSED`）断言抛 `STATE_TRANSITION_VIOLATION`。文件路径：`order-service/src/test/java/com/payment/order/application/OrderApplicationServiceTest.java`。
- [x] T023 [P] 在 `PaymentCallbackContractTest` 补充对 `stack.order`（RecordingOrderGateway）的断言：SUCCESS 时 `order.succeededRequests` 恰好 1 次、FAILURE / UNKNOWN 时为 0 次（对称于既有的 fulfillment 断言）。文件路径：`payment-service/src/test/java/com/payment/payment/contract/PaymentCallbackContractTest.java`。
- [ ] T024 落实 FR-009 异常留痕：在 `PaymentResultProcessor.applyAndNotify` 的 order RPC catch 分支，对「订单非法前态拒绝」补充结构化审计 / 告警（不回滚支付成功，仅留痕供人工 / 对账）。文件路径：`payment-service/src/main/java/com/payment/payment/application/PaymentResultProcessor.java`。
- [ ] T025 本地 MySQL 实跑 `quickstart.md` 全链路：建 SKU（可售）→ 建单（默认 Mock SUCCESS，订单同步 PAID）→ 校验 `GET /orders/{id}` 为 PAID、`GET /payments/{id}` 为 SUCCEEDED；并将结果记录到 `acceptance.md`。
- [ ] T026 [P] 运行 `./mvnw verify`，确认全部测试通过（含 T022 / T023 新增用例），记录测试数与通过数到 `acceptance.md`。
- [ ] T027 运行 `/review`（支付相关 `/payment-review`），确认未绕过模块边界、状态机、幂等或 RPC 契约。
- [ ] T028 更新 `docs/architecture/roadmap.md`：将 `002-payment-order-callback` 标为已验收，Next Feature 推进到 `002 Payment Reliability`（UNKNOWN 收敛 / 重试 / 指标）或 `003 Refund`。

**检查点**: 002 规划产物齐全、测试覆盖闭环、本地 MySQL e2e 跑通、Roadmap 与实现一致。

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 / 2 沿用 001，本 Feature 直接可用。
- Phase 3（US1）是回写核心，必须先于 US2 / US3 稳定。
- Phase 4（US2）/ Phase 5（US3）与 US1 共享 `changed` + `markPaid` 幂等机制，可增量验证。
- Phase 6（Closing）依赖 US1–US3 实现稳定，补齐单测与本地 e2e 后产出验收。

### User Story Dependencies

- **US1（P1）**：核心回写，独立可验。
- **US2（P2）**：依赖 US1 的 `changed` 标志与 `markPaid` 幂等。
- **US3（P3）**：依赖 US1 的 `applyAndNotify` 成功门控。

### Parallel Opportunities

- US1 测试 T007–T009、实现 T010–T015 中无相互依赖的可并行编写（但 `onPaymentSucceeded` 依赖 `markPaid`）。
- US2 / US3 测试与实现可并行验证。
- Phase 6 的 T022 / T023 / T024 可并行补测，T025–T028 顺序收尾。

## Implementation Strategy

### MVP First（US1）

1. 确认 Phase 1 / 2 基座可用（001 已交付）。
2. 完成 US1（T010–T015）：支付成功驱动订单 PAID + 交易 SUCCEEDED。
3. 运行 `./mvnw verify` 验证 US1 不破坏既有测试。

### Incremental Delivery

1. US1：支付成功回写闭环（核心）。
2. US2：重复 / 乱序 / 延迟回调幂等吸收。
3. US3：仅成功回写，失败 / 未知不回写。
4. Closing：补齐单测缺口、FR-009 留痕、本地 MySQL e2e、review、Roadmap。

### Done Definition

每项任务完成时必须：

- 代码 / 测试 / 文档位于任务指定路径。
- 相关测试通过，且未通过改测试迎合实现。
- 状态机、幂等、UNKNOWN、数据所有权、RPC 边界符合 plan.md。
- 不新增未批准的服务、表、MQ 或真实资金记账路径。

## Notes

- `[P]` 仅表示文件 / 依赖允许并行，不代表可跳过前置业务约束。
- 已落地任务（T001–T021）标记 `[X]` 为事实记录；Phase 6（T022–T028）为关闭本 Feature 的待办，标记 `[ ]`。
- 本 Feature 不引入 Ledger、不执行真实出款；支付成功仅作为触发事实驱动订单 / 交易状态机。
