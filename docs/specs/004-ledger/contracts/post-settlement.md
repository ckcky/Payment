# Contract: 结算记账（Post Settlement）

**方向**：settlement-service → ledger-service（内部 RPC，OpenFeign）
**端点**：`POST /internal/ledger/postings`
**请求体**：`PostingRequest`（common-dto）

## Request（PostingRequest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| sourceType | String | 是 | 固定 `SETTLEMENT` |
| sourceId | String | 是 | settlementBatchId |
| idempotencyKey | String | 是 | `SETTLEMENT:<batchIdempotencyKey>` |
| currency | String | 是 | 币种 |
| entries | List<EntrySpec> | 是 | 结转分录，MUST 平衡 |

**示例（商户周期净额 S=5000，应付→已结）**：

```json
{
  "sourceType": "SETTLEMENT",
  "sourceId": "stl_9",
  "idempotencyKey": "SETTLEMENT:stl_9_idemp",
  "currency": "CNY",
  "entries": [
    {"accountCode": "MERCHANT_PAYABLE", "direction": "DEBIT", "amountMinor": 5000, "entryType": "SETTLEMENT"},
    {"accountCode": "SETTLEMENT_PAYABLE", "direction": "CREDIT", "amountMinor": 5000, "entryType": "SETTLEMENT"}
  ]
}
```

## Response

`201 Created` → `PostingResponse { postingId, status: "POSTED" }`

## 规则

- 仅对已确认且差异可解释的结算批次调用；MVP 不真实出款（仅账务结转）。
- 借贷平衡校验、幂等、失败兜底同支付记账。

## 错误码

同 [post-payment-capture.md](post-payment-capture.md#错误码)。
