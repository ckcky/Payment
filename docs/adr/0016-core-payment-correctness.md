<a id="adr-0054"></a>


- **状态**：Accepted（确认性 ADR：记录既有事实，非新决策）
- **日期**：2026-09-03
- **关联 Feature**：`002-payment-order-callback`（整个 Feature 承载 10 条 MUST 级资金约束，此前无对应 ADR）
- **关联 Constitution 条款**：§II 资金铁律、§III 边界、§V.7 未确认结果不落账

> 本 ADR 为**确认性**纪录：将散落在 `002-payment-order-callback` 代码与 spec 中的 MUST 级资金正确性约束集中落文，避免「代码有约束、文档无依据」的漂移。不引入新决策。

## Context（背景）

`002-payment-order-callback` 是支付/订单/回调主链路，承载了项目最高密度的资金正确性约束，但此前**零 ADR 覆盖**。审计发现这是风险最高的文档-代码缺口。

## Decision（决策 / 既有约束清单）

以下约束**已在代码中落地**，本 ADR 仅确认其权威地位：

| # | 约束 | 代码落点 |
|---|---|---|
| 1 | 支付意图幂等：同 `payment:{orderId}` 不重复产生资金动作 | `uk_payments_idempotency_key` + `OrderEntryIdempotencyService` |
| 2 | 金额铁律：long 分 + currencyCode，禁 float/double | `Money`/`AmountMinor` 全链路 |
| 3 | 任何真实资金变动经 Ledger 复式记账（支付/退款/结算） | `FeignLedgerPostingGateway` ×3 |
| 4 | UNKNOWN 不猜成败：超时/断连/不完整响应一律进 UNKNOWN | `PaymentStatus.UNKNOWN` + 收敛路径 |
| 5 | 并发/重复回调靠幂等吸收，不重复执行不可确认资金动作 | 幂等键 + 状态机 `requireStatus` |
| 6 | 终态冲突：迟到成功不覆盖已失败支付 | `ADR-0007` 终态冲突策略 |
| 7 | 重试仅限幂等调用，通信失败一律重试、业务失败不重试 | `ADR-0012`/`0013` |
| 8 | 渠道回调验签（当前空实现恒放行，接入前不得暴露公网） | `ChannelCallbackSignatureFilter` |
| 9 | 人工收敛端点有 admin-token 守卫（未配置即 503 拒绝） | `ResolveAuthorizationInterceptor` |
| 10 | 资金动作 MUST 有审计日志（`FINANCIAL_AUDIT`） | `StructuredAuditLogger` |

## Consequences（后果）

- 正向：10 条 MUST 级约束首次有 ADR 背书，未来改动须对照本表。
- 风险：约束 #8 验签为空实现，是已知且负责人已接受的风险（见 `ADR-0025`/`0052`、`technical-solution.md` §5.2）。
- 后续：若某条约束被放宽，须写新 ADR supersede 本条对应项。
