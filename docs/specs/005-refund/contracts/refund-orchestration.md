# Contract: Refund 跨域编排 RPC 契约

**Feature**: `005-refund` | **Date**: 2026-08-29 | **Spec**: [spec.md](../spec.md) | **Data Model**: [data-model.md](../data-model.md)

> 本文件汇总 refund-service 作为**编排方**的全部出站 RPC 契约。
> 标注：**已实现** = 代码中已存在；**[改]** = 本 Feature 变更（向后兼容）；**[新]** = 本 Feature 新增。
> 统一约定：同步 HTTP + OpenFeign；错误响应体 `ApiError`（common-core）；跨服务只传 `common-dto` 中的 DTO，不共享实体。

## 0. 契约总览

```text
refund-service (8085, schema `refund`)
  ├─→ payment-service      (8084)  query-amount / refund-attempt        已实现 + [改]
  ├─→ fulfillment-service  (8086)  on-refund                            [新]  ← 缺口 G2
  ├─→ entitlement-service  (8087)  on-refund                            已实现
  └─→ ledger-service       (8090)  postings                             端点已实现 / 退款侧调用 [新]  ← 缺口（004-ledger US2）
```

---

## 1. refund-service → payment-service

**已实现**（`infra/client/PaymentRefundFeignClient.java`）；`RefundAttemptResponse` 为本 Feature **[改]**。

### 1.1 查询可退金额与支付状态

`POST /internal/payments/query-amount` → `200`

- **请求** `PaymentAmountQueryRequest { paymentId }`
- **响应** `PaymentAmountQueryResponse { paymentId, orderId, userId, paidAmountMinor, currencyCode, status }`
- **用途**：退款受理前取已支付金额与币种，供 `RefundPolicy` 累计校验；只查询事实，不改支付状态。
- **不变**（本 Feature 无变更）。

### 1.2 发起渠道退款尝试

`POST /internal/payments/refund-attempt` → `200`

- **请求** `RefundAttemptRequest { refundId, paymentId, orderId, userId, amountMinor, currencyCode, reason, idempotencyKey }`
- **响应** `RefundAttemptResponse { refundId, status, channelReference, refundedAmountMinor }` —— `refundedAmountMinor` 为 **[改] 新增**

| 字段 | 类型 | 说明 |
|---|---|---|
| status | String | `SUCCEEDED` / `FAILED` / `UNKNOWN`（渠道结果，UNKNOWN 不得被当作成功或失败） |
| channelReference | String | 渠道退款引用 |
| **refundedAmountMinor** | long | **[改] 新增**：渠道**实际**退回金额（最小货币单位） |

**规则**：

- `refundedAmountMinor` 仅在 `status = SUCCEEDED` 时有业务意义；`FAILED` 为 0；`UNKNOWN` 为 0（未确认）。
- refund-service 侧按 `refundedAmountMinor` 判定全额/部分成功：`== amountMinor` → `SUCCEEDED`；`0 < r < amountMinor` → `PARTIALLY_SUCCEEDED`；其余非法组合 → `UNKNOWN` + 告警（data-model INV-2/INV-5）。
- **兼容性**：新增字段为向后兼容扩展；未升级的 payment-service 返回缺失值时，refund-service MUST 按 ADR-0016 的默认策略处理（建议：缺失 → 视为全额成功，保持既有行为）。

**错误**：`NOT_FOUND`（支付不存在）、`STATE_TRANSITION_VIOLATION`（支付非 `SUCCEEDED`）。

---

## 2. refund-service → fulfillment-service **[新]**（缺口 G2）

**端点**：`POST /internal/fulfillments/on-refund`（新增，见 ADR-0017）
**方向**：refund-service → fulfillment-service（8086）
**触发时机**：退款被确认为 `SUCCEEDED` 或 `PARTIALLY_SUCCEEDED` 时；**`UNKNOWN` 不触发**。

### Request（RefundFulfillmentRequest，`common-dto` 新增）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| refundId | Long | 是 | 退款 ID（下游幂等依据） |
| paymentId | Long | 是 | 原支付 ID（履约以 `sourcePaymentId` 定位） |
| orderId | String | 是 | 订单 ID |
| orderItemIds | List&lt;String&gt; | 否 | 退款明细对应的订单明细（取自 `RefundItem.orderItemId`）；空表示整单 |
| refundedAmountMinor | long | 是 | **实际**退款金额（部分退款时为已退部分） |
| currencyCode | String | 是 | 币种 |
| reason | String | 是 | 退款原因 |

```json
{
  "refundId": 42,
  "paymentId": 7,
  "orderId": "ord_1",
  "orderItemIds": ["oi_1"],
  "refundedAmountMinor": 300,
  "currencyCode": "CNY",
  "reason": "customer request"
}
```

### Response（RefundFulfillmentResponse）

| 字段 | 类型 | 说明 |
|---|---|---|
| refundId | Long | 回显退款 ID |
| status | String | `CANCELLED` / `SKIPPED` / `REJECTED` |

- `CANCELLED`：履约被取消（当前仅 `PENDING → CANCELLED` 可行，`Fulfillment.cancel()`）。
- `SKIPPED`：无待履约记录，或履约已处于不可撤销状态（如 `DELIVERED` / `PROCESSING` / `FAILED` / `CANCELLED`）——**由 fulfillment 自身状态机决定**，refund-service 不得要求其强行改写（Constitution 边界 #6）。
- `REJECTED`：请求语义非法（由 fulfillment 判定）。

**规则**

- 幂等：以 `refundId`（+ `orderItemIds`）为幂等依据；重复调用 MUST 返回首次结果，不重复取消。
- **失败语义**：RPC 抛异常/超时 → refund-service 记为一次 `FAILED` 的 `RefundPostProcessAttempt`，**不回滚退款成功**（Saga，禁 2PC）。
- `SKIPPED` / `REJECTED` **不算失败**（不递增 `refund.post_process_failed`），仅记录事实。

**错误码**：`NOT_FOUND`（无对应履约记录 → 建议映射为 `SKIPPED`）、`STATE_TRANSITION_VIOLATION`（状态机拒绝 → 映射为 `REJECTED`）、`INVALID_ARGUMENT`。

---

## 3. refund-service → entitlement-service

**已实现**（`infra/client/EntitlementFeignClient.java`）；本 Feature 仅**改造调用方式**（失败由静默吞掉改为可追踪），契约字段按 ADR-0016 可能扩展。

**端点**：`POST /internal/entitlements/on-refund` → `200`

- **请求** `RefundPostProcessRequest { refundId, paymentId, orderId, userId, reason }`
- **响应** `RefundPostProcessResponse { refundId, status }` —— `status` 取值 `REVOKED` / `NOOP` / `FAILED`
- **规则**：退款成功后请求权益吊销；下游按自身规则处理，refund-service 不直接改 entitlement 内部状态；失败不回滚退款成功。
- **[改] 建议扩展**（依赖 ADR-0016）：请求体增 `refundedAmountMinor` / `currencyCode`，使部分退款可按实际金额处理权益；未升级时下游维持整单吊销语义。

> **现状问题（本 Feature 修复）**：`RefundApplicationService.java:113` 当前为 `catch (RuntimeException ignored)`，后处理失败被静默吞掉，无法满足「后处理失败可独立追踪」。修复后改为落 `RefundPostProcessAttempt` + `refund.post_process_failed`。

---

## 4. refund-service → ledger-service【端点已实现 / 退款侧调用为新增】

**端点**：`POST /internal/ledger/postings` → `201`（`ledger-service` `api/LedgerController.java`，端口 8090）
**方向**：refund-service → ledger-service（refund 侧网关为 **[新]**，对齐 `payment-service/.../FeignLedgerPostingGateway.java`）

### Request（PostingRequest，`common-dto` 既有）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| idempotencyKey | String | 是 | `REFUND:<refundIdempotencyKey>` |
| sourceType | String | 是 | 固定 `REFUND`（`LedgerSourceType.REFUND`） |
| sourceId | String | 是 | `<refundId>` |
| currency | String | 是 | 币种（MVP 仅 `CNY`） |
| entries | List&lt;EntryRequest&gt; | 是 | 分录，借贷 MUST 平衡 |

`EntryRequest { accountId, direction, amountMinor, entryType }` —— 注意：字段为 **`accountId`（科目 ID）**，非 `accountCode`；`entryType` 用 `REFUND`。

**示例（部分退款：申请 1000，已退 300）**：

```json
{
  "idempotencyKey": "REFUND:ref_42_idem",
  "sourceType": "REFUND",
  "sourceId": "42",
  "currency": "CNY",
  "entries": [
    {"accountId": 2, "direction": "DEBIT",  "amountMinor": 300, "entryType": "REFUND"},
    {"accountId": 1, "direction": "CREDIT", "amountMinor": 300, "entryType": "REFUND"}
  ]
}
```

（科目语义：DEBIT `MERCHANT_PAYABLE` / CREDIT `CUSTOMER_CASH`，与支付记账方向相反；`accountId` 以账本预置科目实际 ID 为准。）

### Response

`201` → `PostingResponse { postingId, idempotencyKey, sourceType, sourceId, currency, status, entries }`

### 规则

- **记账金额 = `refundedAmountMinor`（实际退款金额）**，非申请金额（FR-009 / spec US4 场景 2）。
- 仅对**已确认**退款（`SUCCEEDED` / `PARTIALLY_SUCCEEDED`）记账；`UNKNOWN` / `PROCESSING` / `FAILED` / `REJECTED` **不记账**（Constitution §V.7）。
- 幂等：相同 `idempotencyKey` 返回首次 `Posting`，不重复生成分录。
- 借贷不平衡 → 账本侧拒绝（`Posting` 聚合根强校验），不落任何分录。
- **失败兜底**：RPC 失败/超时 → **不回滚**退款成功事实，记 `ledger.posting_failed` + `RefundPostProcessAttempt(target=LEDGER, status=FAILED)`，由重试/对账补齐（ADR-0018，Saga 禁 2PC）。

### 其他账本端点（供验收/对账）

- `GET /internal/ledger/postings?idempotencyKey=...` —— 按幂等键回查
- `GET /internal/ledger/balance` —— 全局借贷平衡性校验
- `GET /internal/ledger/entries?sourceType=REFUND&sourceId=<refundId>` —— 按来源追溯分录

---

## 5. 跨契约一致性规则

| # | 规则 | 说明 |
|---|---|---|
| R1 | 后处理与记账的**触发条件唯一**：仅 `SUCCEEDED` / `PARTIALLY_SUCCEEDED` | `UNKNOWN` 不触发（FR-007） |
| R2 | 每个目标（FULFILLMENT / ENTITLEMENT / LEDGER）一次调用 = 一条 `RefundPostProcessAttempt` | 失败可独立追踪（FR-005） |
| R3 | 任一目标失败**不回滚**退款成功，也不影响其他目标继续编排 | Saga + 幂等，禁 2PC（Constitution §IV） |
| R4 | 幂等依据统一：`refundId`（后处理）/ `REFUND:<refundIdempotencyKey>`（记账） | 重复收敛只触发一次（FR-006） |
| R5 | 金额一律 `long` 最小货币单位；校验在 refund 受理、渠道结果回传、记账三处**分别**执行 | 不依赖上游校验（technical-solution §4.5） |
| R6 | 出站 Feign 超时 `[目标]` connect 1s / read 3s；后处理有限退避重试（3 次 / 1s-2s-4s） | 当前沿用 OpenFeign 默认值 |
