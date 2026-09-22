---
description: 对照 Constitution、docs/standards 与工程规范做一次代码 Review（改动范围可选）
argument-hint: [改动范围 | PR | 分支 | 路径]
---

# 代码 Review

对照项目约束 Review 改动，输出缺口清单，不静默修改代码（除非明确要求修复）。

> 判据的权威来源：`.specify/memory/constitution.md`、`docs/guides/*.md`、`docs/standards/*.md`。**不要凭记忆引用条款号**——条款号写错即视为 Review 缺陷（见 `docs/standards/documentation-review.md`）。

## 评审判据

1. **架构边界**（Constitution §IV 架构边界；分层见 §Engineering Standards 2）：分层 `api → application → domain ← infra` 依赖单向；不跨服务直改他服务数据；`domain` 不依赖框架层。
2. **资金正确性**（Constitution §II，最高优先级）：金额用 `long` 最小货币单位（`*Minor`），禁止 `float`/`double`（`Money` 值对象**当前未启用**）；资金变动必须经 ledger 复式记账且借贷平衡，禁止直改余额字段。
3. **一致性**（Constitution §V）：资金入口有幂等键 + DB 唯一约束兜底；下单入口为 Redis 防重（`Idempotency-Key`）；状态机显式单向、集中在 domain 状态转换函数；跨服务用同步 RPC + 幂等 + 调用方补偿台账，异步通知走 Redis 事务消息通道（ADR-0074），**禁用 2PC/XA**；未知支付状态不猜成败。
4. **工程规范**（engineering-standards）：DTO/Entity 分离；错误码统一；`@Transactional` 只放 application 层且只覆盖本地事务。
5. **测试**：资金逻辑有测试；无删除测试或改测试迎合错误实现（Constitution §AI Development 3/4）。
6. **文档**：重要决策有 ADR；特性目录为 `docs/specs/<stage>/<feature>/`，具备 Spec Kit **四件套**（`spec.md` / `plan.md` / `tasks.md` / `acceptance.md`）；代码与 Spec 一致；文档写法遵循 `docs/standards/`。

## 输出

- 按严重度列出发现（正确性 > 边界 > 规范 > 可维护性）。
- 每条给出：文件位置、问题、为什么违反哪条约束（**引用可核对的条款名**）、建议修复。
- 涉及 Constitution §Governance › 人类决策边界的变更，标注为「需人类确认」，不自行改。
- 不要为了找问题而制造没有实际意义的问题。
- 若发现文档与代码/规范互相矛盾，MUST 报告 `DOCUMENTATION_DRIFT`，不得静默择一。
