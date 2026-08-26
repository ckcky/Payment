---
name: observability
description: 可观测性规范——Micrometer 指标、结构化日志（traceID 关联）、链路追踪、业务告警与 SLO。涉及日志/指标/告警/资金审计日志必读。
---

# 可观测性规范

来源：`.specify/memory/constitution.md` Observability、`docs/guides/engineering-standards.md` §7。

核心业务流程（Product→Order→Payment→…→Settlement 主链，及 Refund 链）都 MUST 具备可观测性。

## 四大支柱（§6）

1. **Metrics**：请求量、延迟、错误率、关键业务计数（支付成功/失败/超时/退款），用数值计数，不用日志凑数。实现：Micrometer。
2. **Logs**：结构化日志（logback，含 traceId、orderId、paymentId 关联 ID）；**资金动作 MUST 有审计日志**；敏感信息（卡号、密钥）脱敏。
3. **Traces**：跨领域、跨渠道调用用 traceId/spanId 串联。实现：Micrometer Tracing 在跨服务传播。
4. **Business Alerts**：对「支付状态未知堆积」「对账差异」「退款失败」「重试耗尽」等业务异常 MUST 有告警，而非只告警基础设施。

## SLO（§6.5）

核心接口定义目标（支付接口可用性、P99 延迟、对账达成率），有错误预算意识。

## 约束

可观测性 MUST 轻量起步（结构化日志 + 计数器 + traceID 关联），不得为 Trace 提前上重基础设施——「可观测」优先于「可观测工具链的复杂度」。

## 落地要点（engineering-standards §7）

- 每个 HTTP/RPC 入口打结构化日志 + 关联 ID，全链路透传 traceId。
- 资金动作（支付、退款、结算、记账）单独一条审计日志，含幂等键、金额、币种、状态流转。
- 状态进入 UNKNOWN 时记 ERROR 级日志并计数，供告警触发。
- 脱敏规则在序列化层统一处理，禁止在业务代码里「顺手打全字段」。
