# Plan: 支付编排职责归位（Feature 016 / ADR-0065）

> Spec 阶段见 `spec.md`；本文件是 **Plan 阶段**产物：现状检查、目标架构、数据模型 delta、契约预览、涉及文件、依赖顺序、风险与验证。本 Feature **不写代码**（实现阶段另立 Task 后执行）。

## 1. 现状检查（已核实，be136d3）

**当前拓扑（payment 为扇出中心）：**

```
payment-service                         order-service                   fulfillment-service        entitlement-service
─────────────────                      ──────────────                  ───────────────────        ──────────────────
PaymentResultProcessor                 OrderApplicationService         FulfillmentApplicationService
  ├─ :73 fulfillmentGateway.notify ✗   └─ onPaymentSucceeded(:196)      └─ EntitlementGateway(:7)
  ├─ :78 orderGateway.notify              ├─ markPaid                  履约完成 ───────────────────▶ 授予权益 (保留)
  ├─ :82 catch 409 → autoRefund ✗         ├─ transaction.succeed()
  └─ :90 ledgerGateway.post ✔            └─ confirmStock
PaymentApplicationService
  └─ :118 fulfillmentGateway.notify ✗
PaymentAutoRefundService.autoRefund(paymentNo)  ← 自动退款发起方(错误归属)
```

**判定**：payment 同时承担「通知订单 + 直调履约 + 自行退款 + 记账」4 件事，order 只是状态接收方。与目标架构相反（G1~G4）。

## 2. 目标架构（order 为编排者，payment 为能力提供方）

```
payment-service                              order-service                                  fulfillment-service        entitlement-service
─────────────────                           ──────────────                                 ───────────────────        ──────────────────
PaymentResultProcessor                      TransactionApplicationService (transaction 层) FulfillmentApplicationService
  ├─ orderGateway.notifyPaymentSucceeded ✓  ├─ 判定: 正常 / surplus                        └─ EntitlementGateway(:7)
  └─ ledgerGateway.postPaymentCapture ✓     │   正常 ▼ 委派                                履约完成 ───────────────▶ 授予权益 (保留)
PaymentGateway.refund(...) ◀──── 调用 ────┐ │                                             │
  (被 order 调用的退款命令执行入口)         ▼ │                                            ◀┘
  └─ PaymentRefundService (执行)           OrderApplicationService (order 层)
   ▲ surplus(直调,不经 order 层)             ├─ markPaid + transaction.succeed()
   └──────────────┬─────────────────────────┤ + confirmStock
                  │                         └─ FulfillmentGateway.notify ✓
                  └── surplus: PaymentGateway.refund(transactionNo, paymentNo) ✓ (transaction 层发起)
```

**职责边界（Constitution §7 对齐；分工经负责人 2026-09-06 纠正明确）：**
- **order 层**：订单创建 / 商品 / 金额 / 订单状态机；`order_no ↔ transaction_no` 1:1；**支付成功后的订单侧动作**——markPaid + transaction.succeed() + confirmStock + 驱动履约（`FulfillmentGateway.notify`），由 transaction 层判定「正常到账」后委派执行。**confirmStock 与履约驱动属 order 层，不在 transaction 层。**
- **transaction 层（新增）**：交易动作编排——判定正常 / surplus；正常 → 委派 order 层执行；surplus → 以 `transactionNo + paymentNo` 发起自动退款。`transaction_no : payment_no` 1:N。
- **payment 层（能力提供方）**：渠道支付（`payment_no : payment_attempts = 1:1`——每张支付单仅一条渠道尝试记录，渠道重试在同一行内 `retry_count` 递增；`transaction_no : payment_no` 1:N）、记账（`ledgerGateway.postPaymentCapture`）、退款命令执行（`PaymentGateway.refund`）。**移除**对履约 / 自动退款的直调。
- **fulfillment / entitlement**：不变，`fulfillment → entitlement` 保留。

## 3. 数据模型 Delta（仅增量，不重建）

| 对象 | 变更 | 说明 |
|---|---|---|
| `common-dto` `RefundAttemptRequest` | **新增** `String transactionNo` | 自动退款业务上下文（FR-005）；幂等键改 `autorefund:{transactionNo}:{paymentNo}` |
| `common-dto` `PaymentSucceededRequest` | **新增** `String transactionNo` | payment→order 透传（FR-006）；payment 侧已知其所属 transaction |
| `order.transactions` | 已含 `transaction_no`（1:1 order）；surplus 判定基于 `transaction.status`（已 PAID？） | 不新增冗余 payment 列表列；surplus 由「传入 paymentNo + 本 transaction 已 SUCCEEDED」直接判定 |
| `payment.payments` | 已有 `transaction_id`（指向 transaction，1:N 已建立，ADR-0064） | 不变 |
| `payment.payment_attempts` | **复用为渠道交互记录表**：退款尝试也落此表（FR-017 三步链第②步）——`payment_no` 关联 + `channel_reference` = 渠道退款流水号（唯一约束已有 `uk_attempts_channel_reference`）；需区分支付/退款尝试（`attempt_type` 列或按渠道流水号前缀，`[待定]` 实现期定） | 修复 N4：退款渠道流水号落库 |
| `payment.refunds` | **不加** `channel_reference` 列（对账事实经退款尝试记录获取真实流水号） | N4 修复后 `RefundFactsService` 弃合成引用 |
| `payment.refunds` / `refund_items` | 退款域已在 payment（ADR-0064 #5） | 不变 |
| **文档修正** `data-model.md` | Transaction 1:1 Payment → **1:N** | 对齐 ADR-0064 现实（FR-011 / N1） |

> 说明：surplus 判定**不依赖** order 预先持有 payment 列表。订单收到第二笔 `paymentNo` 成功通知时，若 `transaction.status == SUCCEEDED`（首笔已置），即判 surplus。无需新增关联表，降低数据模型改动面。

## 4. 契约预览（Contracts）

### 4.1 payment → order（支付成功通知，改造）

`POST /internal/orders/{orderNo}/payment-succeeded` 或既有 `OrderPaymentRpcController` 端点：

```
PaymentSucceededRequest {
  String orderNo;          // 既有
  String paymentNo;        // 既有
  String transactionNo;    // [新增] FR-006
  String channelReference; // 既有 (out_channel_no 改名另立待办)
  long   amountMinor;      // 既有
  String currencyCode;     // 既有
}
```

### 4.2 order → payment（自动退款命令，新增 / 改造）

`POST /internal/payments/{paymentNo}/refunds` 或既有退款端点，请求体：

```
RefundAttemptRequest {
  String transactionNo;    // [新增] FR-005
  String paymentNo;        // 既有
  String orderNo;          // 既有
  long   amountMinor;      // 既有 (surplus 全额退)
  String currencyCode;     // 既有
  String channelCode;      // 既有
  String idempotencyKey;   // [改] autorefund:{transactionNo}:{paymentNo}
}
```

**退款执行三步链（FR-017）**：payment 退款域收到请求后——①生成 `refundNo`（幂等 + intake lock + 防超退）→ ②落退款渠道尝试记录（复用 `payment_attempts`，`channel_reference` = 渠道退款流水号）→ ③调 `channel.refund` 外部渠道，三态收敛退款状态机；成功后后处理编排（履约撤销→权益吊销→记账冲正）不变。

### 4.3 order → fulfillment（履约通知，改造触发方）

`FulfillmentGateway.notifyPaymentSucceeded(...)` 签名不变，仅**调用方**由 payment 改为 order **层**（transaction 层判定「正常到账」后委派 order 层调用；请求体已含 orderNo / paymentNo / transactionNo）。

> 详细契约以 `/speckit-tasks` 生成的 `contracts/` 为准；本 Plan 仅给字段级预览。

## 5. 涉及文件清单（实现阶段参考，本 Plan 不改动）

**payment-service（瘦身 / 能力保留）**
- `application/PaymentResultProcessor.java`：移除 `fulfillmentGateway` 字段与 `:73` 调用、移除 `autoRefundGateway` 字段与 `:82` 调用（保留 `orderGateway` / `ledgerGateway`）。
- `application/PaymentApplicationService.java`：移除 `:118` 同步 charge 路径的 `fulfillmentGateway.notifyPaymentSucceeded`。
- `application/PaymentAutoRefundService.java`：`autoRefund(paymentNo, ex)` → 改为被 `PaymentGateway.refund` 触发的执行器；`autoRefund` 签名补 `transactionNo`（或新增 `refundByOrder(transactionNo, paymentNo)`）。
- `application/PaymentRefundService.java`：按 FR-017 三步链改造——调 `channel.refund` 前落退款渠道尝试记录（复用 `payment_attempts`，`channel_reference` = 渠道退款流水号）。
- `refund/application/RefundFactsService.java`：对账退款事实的 `channelReference` 弃 `"refund-{id}"` 合成引用，改自退款尝试记录取真实渠道流水号（N4）。
- 新增 `infra/client` / controller 暴露 `PaymentGateway.refund(...)` 供 order 调用（或复用既有退款端点）。
- 清理：`FulfillmentGateway` / `AutoRefundGateway` 接口与实现（若不被他处引用则降级 / 删除）。

**order-service（升为编排者）**
- 新增 `application/TransactionApplicationService.java`：接收 `PaymentSucceededRequest`，判定正常 / surplus；正常 → **委派 `OrderApplicationService`**（order 层）执行状态推进与履约驱动；surplus → 调 `PaymentGateway.refund(transactionNo, paymentNo)`。**不直接执行 confirmStock / 履约驱动**。
- `OrderApplicationService.onPaymentSucceeded(:196-236)`：承接 transaction 层「正常到账」委派——markPaid + transaction.succeed() + confirmStock + `FulfillmentGateway.notify`（负责人确认：**confirmStock 与履约驱动属 order 层**）。
- 新增 `infra/client/PaymentGateway.java`（`refund(transactionNo, paymentNo, ...)` → Feign payment-service；surplus 路径由 transaction 层直调）。
- `application/FulfillmentGateway.java`：保留，改由 order 层调用。

**common-dto**
- `rpc/RefundAttemptRequest.java`：新增 `transactionNo`。
- `rpc/PaymentSucceededRequest.java`：新增 `transactionNo`。

**fulfillment-service / entitlement-service**
- 无改动（`EntitlementGateway` 保留）。

**文档**
- `docs/specs/001-core-business-model/data-model.md`：Transaction 1:1 → 1:N（FR-011）。
- `docs/adr/0024-multi-payment-per-transaction.md`（ADR-0064）：#4 标注 `Superseded by ADR-0065`。
- `docs/adr/README.md`：注册 ADR-0065、下一可用编号改 0055。

## 6. 依赖顺序 / 落地阶段（实现期 Task 拆分参考）

按「先能力、后迁移、再删依赖」降低回归面：

1. **T1 契约前置**：`RefundAttemptRequest` / `PaymentSucceededRequest` 加 `transactionNo`；payment 暴露 `PaymentGateway.refund(...)`（仍可由 payment 自测）；payment 退款域落**退款渠道尝试记录**（`payment_attempts` 复用）+ `RefundFactsService` 改真实渠道流水号（FR-017 / N4）。—— 不影响现有链路。
2. **T2 order transaction 层 + order 层委派**：新增 `TransactionApplicationService`，实现「正常 / surplus」判定；正常路径**委派 `OrderApplicationService`**（markPaid + transaction.succeed() + confirmStock + FulfillmentGateway.notify，均由 order 层执行），surplus 路径直调 PaymentGateway.refund。先接 `OrderPaymentRpcController` 的支付成功入口。
3. **T3 payment 瘦身**：`PaymentResultProcessor` / `PaymentApplicationService` 移除 `fulfillmentGateway` / `autoRefundGateway` 直调，仅保留 `orderGateway.notify` + `ledgerGateway.post`；`PaymentAutoRefundService` 改为被 order 调用。
4. **T4 测试迁移**：`PaymentCallbackConflictScenarioTest` 迁为 `TransactionCallbackConflictTest`（order 视角：第二笔 → surplus → 退款 SUCCEEDED，0 个 409）。
5. **T5 文档收口**：data-model 1:N、ADR-0064 #4 supersede 指针、本 spec/plan 与代码对齐（FR-011）。

> T2 与 T3 之间存在短暂「双触发」窗口（payment 仍直调履约 + order 也调）。实现期 MUST 在 T3 完成前确保 payment 侧扇出已移除，避免履约双触发；建议 T2 先用 feature flag / 临时分支隔离，或 T3 紧随 T2 同一提交完成。

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 链路变长 payment→order→fulfillment，order→fulfillment 失败的韧性 | 履约可能漏触发 | 保留「catch 吞掉不回滚 + 重试 / 对账兜底」语义（与现状一致）；不得因迁移引入订单状态回滚 |
| surplus 双成功并发 | 两笔都判正常 / 都退 | order 基于 `transaction` 状态机权威判定；必要时 version 乐观锁保护 transaction 行 |
| 测试迁移遗漏 | `PaymentCallbackConflictScenarioTest` 仍断言 409 | 迁移到 order 视角并显式断言「order 不再抛 409」（FR-010/FR-015）；禁止删测试 |
| payment 侧 FulfillmentGateway / AutoRefundGateway 被他处引用 | 编译失败 | T3 前全量 grep 引用点（fulfillment / autoRefund），确认仅 PaymentResultProcessor / PaymentApplicationService 使用 |
| 双触发（T2/T3 窗口） | 履约被执行两次 | T2/T3 合并提交或 feature flag 隔离（见 §6） |
| 文档漂移未同步 | 后人误解 Transaction 关系 | FR-011 强制 data-model + ADR-0064 同步 |

## 8. 验证方式

- **单元 / 集成测试**：
  - `TransactionApplicationServiceTest`：正常到账 → **委派 `OrderApplicationService`** 执行 markPaid / confirmStock / 履约驱动（mock 验证由 order 层执行，transaction 层不直接触发履约）；surplus → 调 `PaymentGateway.refund(transactionNo, paymentNo)` 且 `RefundAttemptRequest` 含 `transactionNo`、幂等键含 `transactionNo`。
  - `TransactionCallbackConflictTest`：首笔成功 → PAID；第二笔 → surplus → 退款 SUCCEEDED，断言 order **0 次**抛 `OrderNotPayableException`。
  - `PaymentResultProcessorTest`：支付成功后**仅** `orderGateway` + `ledgerGateway` 被调用（mock 验证 `fulfillmentGateway` / `autoRefundGateway` 未被调用）。
- **端到端（demo）**：建单 → 选渠道 PM-1 成功 → 断言 PAID + 履约 + 权益；换渠道 PM-2 成功 → 断言 order 判定 surplus → 退款单 SUCCEEDED。可补 `scenario-duplicate-payment.sh`（负责人此前询问过该 demo 项）。
- **门禁**：`mvn -o clean verify -fae` 全量通过；`RefundAttemptRequest` / `PaymentSucceededRequest` 无数值 ID；金额路径无 `float`/`double`。
- **静态检视**：payment-service 不再 import / 持有 `FulfillmentGateway` / `AutoRefundGateway`。
