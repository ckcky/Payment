---
name: observability
description: 可观测性规范导航——指标、结构化日志（traceId 关联）、链路追踪现状、业务告警与 SLO。涉及日志/指标/告警/资金审计日志时必读。事实以 engineering-standards §7 与 L0 为准。
---

# 可观测性规范（导航层）

> **本技能不定义可观测性事实**。当前实现口径以 [docs/guides/engineering-standards.md](../../../docs/guides/engineering-standards.md) §7（Observability，Constitution §Observability）与各服务 System Design §11 为准。

## 四大支柱

1. **Metrics**：请求量、延迟、错误率 + 关键业务计数（支付成功/失败/超时、退款、履约、对账差异、结算），用数值计数、不用日志凑数。实现：Micrometer。
2. **Logs**：结构化日志（logback）含 `traceId` / `orderId` / `paymentId` 关联字段；**资金动作 MUST 有审计日志**（`FINANCIAL_AUDIT` logger）。
3. **Traces**：当前实现为 `TraceIdFilter` + Feign 头透传 + MDC 关联 `traceId`（后台任务经 `TraceContext.runWithNewTrace(...)` 标注）。
4. **Business Alerts**：对「支付状态未知堆积」「对账差异」「退款失败」「重试耗尽」等**业务异常** MUST 告警，而非只告警基础设施。

## SLO

核心接口定义目标（可用性 / P99 延迟 / 对账达成率），有错误预算意识。观测基线与高基数政策见 [ADR-0083](../../../docs/adr/README.md)（🟡 Proposed，**尚未进入 L0**——未裁决前不得当作当前事实）。

## 当前状态（务必区分「已实现」与「未落地」）

| 能力 | 现状 |
|---|---|
| 结构化日志 + `traceId` 关联 | ✅ 已实现（含 `AccessLogFilter` 统一访问日志，ADR-0068） |
| 资金审计日志 | ✅ 已实现（`StructuredAuditLogger`，`FINANCIAL_AUDIT`） |
| Micrometer 指标 + Prometheus / Grafana / Loki / Promtail | ✅ 已实现 |
| 业务告警规则 | ✅ 已实现（`deployment/prometheus/rules/`） |
| **Micrometer Tracing / `spanId` 标准化** | ❌ **未落地**——当前不上独立分布式追踪基础设施 |
| **敏感数据脱敏** | ❌ **本期不做**（ADR-0027：类与配置一并删除，masking hook 保留但生产零调用）；**接入真实渠道前 MUST 重新引入** |
| Testcontainers 真库测试 | ⚠️ 仅用于**需要真库语义**的集成测试（ADR-0081），非全量 Integration Test 默认载体 |

## 约束

可观测性 MUST 轻量起步（结构化日志 + 计数器 + traceId 关联），不得为 Trace 提前上重基础设施——「可观测」优先于「可观测工具链的复杂度」。

## 落地要点（engineering-standards §7）

- 每个 HTTP / RPC 入口打结构化日志 + 关联 ID，全链路透传 `traceId`。
- 资金动作（支付、退款、结算、记账）单独一条审计日志，含幂等键、金额、币种、状态流转。
- 状态进入 `UNKNOWN` 时记 ERROR 级日志并计数，供告警触发。
- **脱敏**：本期不启用真实脱敏；新增日志字段时 MUST 先判断是否含敏感信息，含则**不得**写入（而不是依赖脱敏层）。
