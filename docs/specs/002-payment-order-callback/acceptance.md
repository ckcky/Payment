# Feature 002 验收结果

> 记录 `002-payment-order-callback`（支付成功回写订单与交易状态）的验收结论与证据。
> 本文是事实记录，不改变任何已确认的领域边界、状态机、幂等或 RPC 契约。
> 规划产物（plan / tasks / quickstart / acceptance）于 2026-08-28 补齐；实现已在 `001-core-business-model` 主线内落地。

## 验收结论

| 项 | 结果 | 证据 |
|---|---|---|
| 规划产物补齐 | ✅ 完成 | `plan.md` / `tasks.md` / `quickstart.md` / `acceptance.md` 均已产出 |
| 实现对齐 spec（源码核验） | ✅ 通过 | 回写链路代码逐一核对 FR-001~FR-010（见「源码核验」） |
| 单元测试 / 契约测试执行 | ⏳ 待本地执行 | 本沙箱 Maven 启动器损坏（见「环境限制」），需在你已就绪的本地环境跑 `./mvnw verify`（H2，无需 DB） |
| 本地 MySQL 手动 e2e | ⏳ 待本地执行 | 按 `quickstart.md` §2（你已启动 MySQL）跑通后回填本表 |
| 资金记账 / 真实出款 | ✅ 未引入 | 回写仅驱动订单 / 交易状态机，无 Ledger、无真实出款 |

## 源码核验（本环境已完成）

逐一核对实现与 spec 的 FR / SC 映射（文件均为已落地代码）：

| 需求 | 代码位置 | 核验结论 |
|---|---|---|
| FR-001 订单 `PENDING_PAYMENT→PAID`、整单支付、记录 paymentId | `order/.../domain/Order.markPaid`（`requireStatus(PENDING_PAYMENT)`；`paidMinor=totalMinor`；`paymentId`） | ✅ |
| FR-002 交易 `PROCESSING→SUCCEEDED` | `order/.../application/OrderApplicationService.onPaymentSucceeded`（`transaction.start()` 若 PENDING → `transaction.succeed()`）；`order/.../domain/Transaction.succeed` | ✅ |
| FR-003 各领域状态机自驱 | `Order.markPaid` / `Transaction.succeed` 为各自领域方法；payment 仅经 RPC 触发 | ✅ |
| FR-004 / FR-005 重复回调幂等吸收 | `PaymentResultProcessor.applyAndNotify` 的 `changed` 标志 + `Order.markPaid` 返回 false | ✅ |
| FR-006 仅明确成功才回写 | `applyAndNotify`：`if (changed && result.status() == SUCCESS)` 才通知 order / fulfillment；FAILURE / UNKNOWN 不触发 | ✅ |
| FR-007 无论获知途径只触发一次 | 即时返回 / 回调 / `PaymentUnknownResolutionService.resolve` 均走 `applyAndNotify`，`changed && SUCCESS` 单控 | ✅ |
| FR-008 回写失败不回滚支付成功 | `applyAndNotify` 中 `orderGateway.notifyPaymentSucceeded` 包 try/catch，异常吞掉、支付成功事实保留 | ✅ |
| FR-009 非法前态拒绝、不伪造 | `Order.markPaid` 的 `requireStatus` 对非 `PENDING_PAYMENT` 抛 `STATE_TRANSITION_VIOLATION`；⚠️ 异常被外层 catch 吞掉，**未显式留痕**（见 Known Gaps T024） | ⚠️ 部分 |
| FR-010 回写不阻塞 / 不依赖履约 | `onPaymentSucceeded` 仅驱动订单 / 交易；履约经独立 `fulfillmentGateway` RPC，二者解耦 | ✅ |

## 测试执行（待本地环境）

**权威验证（无需 MySQL）**：

```sh
cd C:/Users/user/Desktop/GoProj/PaymentArch
./mvnw verify      # H2 上跑全部单元 / 契约 / 场景测试
```

002 回写语义由以下测试覆盖（已在源码中确认存在，执行数字待你在本地 `verify` 后回填）：

| 验证点 | 测试类 | 覆盖的需求 |
|---|---|---|
| 订单 `PENDING_PAYMENT→PAID` 转换 + 幂等 | `order-service/.../domain/OrderStateMachineTest`（`markPaid` 重复返回 false） | FR-001 / FR-004 / FR-005 |
| 整单支付 `paidMinor = totalMinor` | `order-service/.../domain/OrderInvariantTest` | FR-001 |
| 订单侧端到端编排 | `order-service/.../scenario/SuccessfulPurchaseScenarioTest` | US1 独立验收 |
| 重复成功回调不发布第二次 | `payment-service/.../contract/PaymentCallbackContractTest.duplicateCallbackDoesNotPublishTwice` | FR-004 / FR-005 / FR-007 |
| 迟到失败不覆盖成功 | `payment-service/.../contract/PaymentCallbackContractTest.lateFailureCallbackDoesNotOverwriteSuccess` | FR-006 / FR-007 |
| UNKNOWN 后再 UNKNOWN 不触发新事件 | `payment-service/.../contract/PaymentCallbackContractTest.unknownCallbackAfterUnknownStaysUnknownWithoutNewEvent` | FR-006 |
| 重复回调计数一次 | `payment-service/.../application/PaymentMetricsTest`（`payment.duplicate_callback`） | FR-004 |
| UNKNOWN 收敛只触发一次履约 | `payment-service/.../integration/PaymentUnknownResolutionTest` | FR-007 |

**手动 e2e（需你已启的本地 MySQL）**：按 `quickstart.md` §2 建 SKU → 建单 → 校验 `GET /orders/{id}` 为 PAID、`GET /payments/{id}` 为 SUCCEEDED。

## 已知缺口（关闭本 Feature 前补齐，见 tasks.md Phase 6）

1. **FR-009 异常留痕（T024）**：非法前态（`CANCELLED`/`CLOSED`）已「拒绝伪造成功」，但异常被 `applyAndNotify` 的 catch 吞掉、未显式记录。需补充结构化审计 / 告警供人工 / 对账跟进（不回滚支付成功）。
2. **应用层单测缺口（T022）**：`OrderApplicationService.onPaymentSucceeded` 缺直接单元测试（当前仅领域层 `markPaid` 与订单侧场景测试覆盖）。
3. **OrderGateway 断言缺口（T023）**：payment 侧契约测试断言了 `RecordingFulfillmentGateway`，未断言 `RecordingOrderGateway` 在 SUCCESS（恰好 1 次）/ FAILURE / UNKNOWN（0 次）下的调用次数。

以上缺口不影响 002 主体正确性（回写、幂等、状态机、UNKNOWN 不回写均已落地并通过源码核验），但补齐后验收更完整。

## 环境限制与后续

- **本沙箱 Maven 不可启动**：wrapper 分发的 `plexus-classworlds-2.8.0.jar` 不完整（不含 `Launcher.class`），系统 `MAVEN_HOME` 指向的 3.9.5 亦缺启动器；且本沙箱仅 JDK 25，项目目标 JDK 21。故无法在本环境执行 `./mvnw verify` 或启动服务。
- **请在你的本地环境执行**：你已启动 MySQL 且工具链正常，直接运行 `./mvnw verify`（H2，无需 DB）与 `quickstart.md` §2（本地 MySQL）即可获得真实验收数字与 e2e 证据，回填本表。
- 执行通过后：运行 `/review`（支付相关 `/payment-review`）、将 `002-payment-order-callback` 标为已验收、推进 Roadmap 的 Next Feature（Tasks T026–T028）。

> 本 Feature **不含** Ledger 复式记账与真实出款：回写仅驱动订单 / 交易状态机，符合 [plan.md](plan.md) 与宪法「资金正确性 > 一切」的阶段性边界。
