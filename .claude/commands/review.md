---
description: 对照 Constitution 与工程规范做一次代码 Review（改动范围可选）
argument-hint: [改动范围 | PR | 分支 | 路径]
---

# 代码 Review

对照项目约束 Review 改动，输出缺口清单，不静默修改代码（除非明确要求修复）。

## 评审判据

1. **架构边界**（Constitution §3 / project-structure）：分层 `api → application → domain ← infra` 依赖单向；不跨服务直改他服务数据；`domain` 不依赖框架层。
2. **资金正确性**（§2.2，最高优先级）：金额用 `long`/`BigDecimal` 或 `Money` 值对象，禁止 `float`/`double`；资金变动必须经 ledger 复式记账，禁止直改余额字段。
3. **一致性**（§4）：资金入口有幂等键 + 唯一约束；状态机显式单向、集中在 domain 状态转换函数；跨服务用 Saga + Outbox + 幂等消费；未知支付状态不猜成败。
4. **工程规范**（engineering-standards）：DTO/Entity 分离；错误码统一；`@Transactional` 只放 application 层且只覆盖本地事务。
5. **测试**：资金逻辑有测试；无删除测试或改测试迎合错误实现。
6. **文档**：重要决策有 ADR；特性有 `specs/<feature>/spec.md`；代码与 Spec 一致。

## 输出

- 按严重度列出发现（正确性 > 边界 > 规范 > 可维护性）。
- 每条给出：文件位置、问题、为什么违反哪条约束、建议修复。
- 涉及 Constitution §8 的变更，标注为「需人类确认」，不自行改。
- 不要为了找问题而制造没有实际意义的问题。