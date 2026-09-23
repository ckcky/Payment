# Contract: 退款记账（Post Refund）

> **历史文档提示（2026-09-22 文档治理）**：本文为历史记录，保留当时的设计划分；其中的 `refund-service` **已并入 payment-service**（ADR-0064，退款域现位于 payment-service 内）。**当前系统事实**见 [docs/architecture/systems/](../../../../architecture/systems/) 与 [technical-solution.md](../../../../architecture/technical-solution.md)。

**方向**：refund-service → ledger-service（内部 RPC，OpenFeign）
**端点**：`POST /internal/ledger/postings`
**请求体**：`PostingRequest`（common-dto）

## Request（PostingRequest）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| sourceType | String | 是 | 固定 `REFUND` |
| sourceId | String | 是 | refundNo |
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
