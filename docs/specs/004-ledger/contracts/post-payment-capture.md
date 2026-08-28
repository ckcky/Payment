# Contract: 支付记账（Post Payment Capture）

**方向**：payment-service → ledger-service（内部 RPC，OpenFeign）
**端点**：`POST /internal/ledger/postings`
**请求体**：`PostingRequest`（common-dto）

## Request（PostingRequest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| sourceType | String | 是 | 固定 `PAYMENT` |
| sourceId | String | 是 | paymentId（或支付幂等键） |
| idempotencyKey | String | 是 | `PAYMENT:<paymentIdempotencyKey>`，唯一约束兜底 |
| currency | String | 是 | 币种（MVP CNY） |
| entries | List<EntrySpec> | 是 | 借贷分录，MUST 平衡 |

`EntrySpec`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| accountCode | String | 是 | 科目编码（如 `CUSTOMER_CASH`/`MERCHANT_PAYABLE`/`PLATFORM_FEE_REVENUE`） |
| direction | String | 是 | `DEBIT` / `CREDIT` |
| amountMinor | long | 是 | 金额（分，> 0） |
| entryType | String | 是 | `PAYMENT_CAPTURE` / `FEE` |

**示例（支付 A=1000，F=60，N=940）**：

```json
{
  "sourceType": "PAYMENT",
  "sourceId": "pay_42",
  "idempotencyKey": "PAYMENT:pay_42_idemp",
  "currency": "CNY",
  "entries": [
    {"accountCode": "CUSTOMER_CASH", "direction": "DEBIT", "amountMinor": 1000, "entryType": "PAYMENT_CAPTURE"},
    {"accountCode": "MERCHANT_PAYABLE", "direction": "CREDIT", "amountMinor": 940, "entryType": "PAYMENT_CAPTURE"},
    {"accountCode": "PLATFORM_FEE_REVENUE", "direction": "CREDIT", "amountMinor": 60, "entryType": "FEE"}
  ]
}
```

## Response

`201 Created` → `PostingResponse { postingId, status: "POSTED" }`

## 规则

- 仅对 `SUCCEEDED` 支付调用；UNKNOWN/PROCESSING 不记账。
- `sum(debit) != sum(credit)` → `400` + `UNBALANCED`。
- 重复 `idempotencyKey` → 返回首次 `PostingResponse`（幂等，不重复落分录）。
- 账本失败由调用方 `LedgerPostingGateway` 重试/兜底，**不回滚支付成功事实**。

## 错误码

| 错误码 | 语义 |
|---|---|
| `UNBALANCED` | 借贷不平衡 |
| `INVALID_ARGUMENT` | 字段缺失/金额≤0/币种不支持 |
| `DUPLICATE` | 幂等键冲突且回查失败 |
| `INTERNAL_ERROR` | 账本内部错误 |
