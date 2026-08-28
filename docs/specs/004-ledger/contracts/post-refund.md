# Contract: 退款记账（Post Refund）

**方向**：refund-service → ledger-service（内部 RPC，OpenFeign）
**端点**：`POST /internal/ledger/postings`
**请求体**：`PostingRequest`（common-dto）

## Request（PostingRequest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| sourceType | String | 是 | 固定 `REFUND` |
| sourceId | String | 是 | refundId |
| idempotencyKey | String | 是 | `REFUND:<refundIdempotencyKey>` |
| currency | String | 是 | 币种 |
| entries | List<EntrySpec> | 是 | 冲正分录，MUST 平衡 |

**示例（退款 R=300，与支付方向相反）**：

```json
{
  "sourceType": "REFUND",
  "sourceId": "ref_7",
  "idempotencyKey": "REFUND:ref_7_idemp",
  "currency": "CNY",
  "entries": [
    {"accountCode": "MERCHANT_PAYABLE", "direction": "DEBIT", "amountMinor": 300, "entryType": "REFUND"},
    {"accountCode": "CUSTOMER_CASH", "direction": "CREDIT", "amountMinor": 300, "entryType": "REFUND"}
  ]
}
```

## Response

`201 Created` → `PostingResponse { postingId, status: "POSTED" }`

## 规则

- 仅对确认退款调用；借贷平衡校验、幂等、失败兜底同支付记账。
- 冲正方向须与原始支付记账相反（使「商户应付」回落）。

## 错误码

同 [post-payment-capture.md](post-payment-capture.md#错误码)。
