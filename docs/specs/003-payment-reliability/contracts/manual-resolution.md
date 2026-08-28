# Contract: 人工收敛内部 RPC（Manual Resolution）

> 对应 spec US4 / FR-008 / FR-009 与 ADR-0006。属 payment-service 内部/受控端点（非对外公开 API）。

## 端点

```http
POST /internal/payments/{paymentId}/resolve
Content-Type: application/json
Authorization: <受控角色凭证，沿用既有安全基线>
X-Operator-Id: <操作人标识，审计必填>
```

### 请求体

```json
{
  "targetStatus": "SUCCESS | FAILED",
  "reason": "必填，人工裁定理由（资金审计依据）"
}
```

### 成功响应 `200 OK`

```json
{
  "paymentId": 123456,
  "status": "SUCCEEDED | FAILED",
  "resolvedBy": "<操作人>",
  "resolvedAt": "2026-08-28T13:00:00Z",
  "downstreamTriggered": true
}
```

- `downstreamTriggered=true`：仅当裁定 SUCCESS 且支付由 UNKNOWN 实际收敛时，复用 002 成功回写链路（订单/交易推进一次）。
- 若支付已是相同终态（幂等重复裁定），返回当前状态、`downstreamTriggered=false`，不重复推进。

### 错误响应

| 场景 | 状态码 | 说明 |
|---|---|---|
| 支付不存在 | 404 | `NOT_FOUND` |
| 支付非 UNKNOWN（已终态） | 409 / 400 | 拒绝覆盖既状态机（ADR-0007），`STATE_TRANSITION_VIOLATION` |
| 缺 `reason` 或无权限/无操作人 | 400 / 403 | 强制理由与权限（ADR-0006） |
| `targetStatus` 非法值 | 400 | 仅允许 SUCCESS/FAILED |

## 审计约束（MUST）

每次成功裁定写入 `FINANCIAL_AUDIT`：操作人、前状态、后状态、理由、时间、paymentId。敏感信息脱敏（Constitution §Security）。

## 不变量

- 仅作用于 UNKNOWN；不提供「改为任意状态」的通配能力（ADR-0006 B 方案否决）。
- 裁定 SUCCESS 与自动查询收敛 SUCCESS 走同一 `PaymentUnknownResolutionService.resolve` 入口，保证「只一次下游动作」。
- 与自动调度器并发时由乐观锁 + 终态吸收保证不双重推进。
