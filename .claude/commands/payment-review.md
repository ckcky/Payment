---
description: 资金路径专项 Review：幂等、复式记账、状态机、未知状态处理、渠道边界
argument-hint: [改动范围 | PR | 分支 | 路径]
---

# 资金路径专项 Review

对支付 / 退款 / 结算 / 账本等资金路径做**最高标准**审查。资金正确性 > 一切（Constitution §II、§Governance › 冲突优先级 1）。

## 必查清单（逐条核对）

### 1. 金额与记账
- 金额是否用 `long` 最小货币单位（`*Minor`）？有无 `float`/`double`？（`Money` 值对象**当前未启用**，不得假定其存在）
- 资金变动是否**全部**经 `ledger-service` 复式记账且借贷平衡？有无直改余额字段？
- 记账幂等键口径是否一致（同步路径与回调路径 MUST 用同一口径，历史缺陷见 ADR-0082 / C-19）？

### 2. 幂等
- 支付/退款/结算入口是否有幂等键？是否持久化 + 数据库唯一约束兜底？
- 重复请求 / 重复回调是否被幂等键 + 状态机吸收，不重复入账？
- 下单入口是否为 Redis 防重四态语义（201 / 200 回放 / 409+Retry-After / fail-open，ADR-0039/0040）？

### 3. 状态机
- Order/Payment/Refund/Settlement 状态机是否显式、单向？
- 状态流转是否集中在 domain 状态转换函数，有无散落直接 set 状态？有无非法跳转？
- 终态吸收口径是否明确（如 FAILED 收迟到 SUCCESS、CLOSED 收迟到 SUCCESS → 只能靠对账发现）？

### 4. 未知状态（最核心）
- 支付结果不确定时是否进 UNKNOWN/PENDING，靠主动查询/回调/对账/人工收敛？
- 有无「超时即当失败/成功直接落账」的猜断？
- 反向路径（查询/重试/超时扫描/退款）的渠道是否恒取自 `payment_attempts.channel_code`，**未重新路由**？

### 5. 渠道边界（Payment ≠ Channel）
- payment 支付层是否只依赖 `PaymentChannel` 端口抽象，未依赖 `infra/channel` 具体实现或渠道 SDK？（ADR-0072；ArchUnit 守卫）
- `payments` 表是否只由支付层写、`payment_attempts` 表是否只由渠道层写？
- 回调签名校验是否按**当前真实状态**描述？——⚠️ JSON 回调路径 `ChannelCallbackSignatureFilter#verifySignature()` 为**预留空实现、恒通过**（ADR-0025/0052，已知风险，**不得**描述为「已验签」）；支付宝 `notify` 走真实 RSA2 验签（spec 030）。
- 敏感字段：**本期不做脱敏**（ADR-0027）。因此审查重点是**日志里有没有写入敏感字段**，而不是「有没有脱敏层」。

### 6. 分布式一致性
- 跨服务资金流转是否同步 RPC + 幂等 + 调用方补偿台账（异步通知走 Redis 事务消息通道，ADR-0074），**未用 2PC/XA**？
- 事务边界是否只覆盖单服务本地事务？
- 失败路径是否有归属（谁补偿、谁重试、谁人工收口）？参考 ADR-0082 的失败恢复归属表。

## 输出

每条发现标注：位置、缺陷、违反的条款（Constitution §II / §V / §III，或具体 ADR）、影响（是否导致多扣/少扣/重复入账/资金不平）、修复建议。

- 涉及 Constitution §Governance › 人类决策边界（尤其 **Payment State Machine Change**、**Security Policy**）标「需人类确认」。
- ⚠️ **不要**把「重复支付自动退款（surplus）」误判为缺陷——它是既有能力（order transaction 层 `surplusRefund` → TXRF → PMRF），已有测试钉死。
