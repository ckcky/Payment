# Commerce & Payment Platform

面向生产环境的 Commerce & Payment Platform（Java / Spring Cloud 微服务），用于学习并实践支付、交易、履约、权益、对账、结算体系与高质量后端工程。不是 CRUD Demo。

> 本文是给 Claude 的**操作总纲**，只保留必须始终在上下文里的硬规则；详细约束见 `docs/`。

## 必读文档（按需加载，不要凭假设动手）

| 文档 | 内容 | 何时读 |
|---|---|---|
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | 最高宪法（v2.0.0）：领域边界、架构、一致性、工程、可观测、AI 原则、人类决策边界 | 任何实现 / 设计前 |
| [docs/adr/](docs/adr/) | 架构决策（0001 微服务、0002 技术栈） | 涉及架构 / 技术选型 |
| [docs/ai-workflow.md](docs/ai-workflow.md) | SDD 流水线、实现前分析、Feature 完成标准 | 做任何 Feature 前 |
| [docs/engineering-standards.md](docs/engineering-standards.md) | 编码 / 测试 / CI / 可观测具体规范 | 写代码 / 测试 / 配置前 |
| [docs/project-structure.md](docs/project-structure.md) | 目录结构与分包约定 | 新建模块 / 文件前 |
| [docs/documentation.md](docs/documentation.md) | 文档体系与层级 | 写 / 改文档前 |

## 硬性红线（MUST NOT）

- 跨领域直接改他领域数据 / SQL 他领域表。
- 核心领域（Payment/Order/Ledger）依赖具体渠道实现（Payment ≠ Channel）。
- 金额用 `float`/`double`；资金变动绕过 ledger 复式记账直改余额。
- 资金入口无幂等键；散落直改 `status` 绕过状态机。
- 无理由新增微服务 / 中间件；引入 2PC/XA 分布式事务（跨服务用 Saga + Outbox + 幂等）。
- 删测试或改测试迎合错误实现。
- 擅自改领域模型 / 状态机 / 服务边界 / 数据库结构 / 公共 API（见宪法 Governance 人类决策边界）。

## 工作方式

1. **先 Spec 后代码**：非简单任务禁止直接写码。先读相关 Spec / ADR / 代码，完成「实现前分析」再定计划。
2. **一次只做一件事**：最小变更，不静默顺带改无关代码。
3. **资金正确性 > 一切**：幂等、复式记账、未知状态不猜成败。
4. **涉及 §8 决策必须暂停**：说明原因 / 影响 / 方案 A / B / 推荐 / 风险，等人类确认。

## 命令与技能

- 命令：`/feature` `/review` `/payment-review` `/test`（见 `.claude/commands/`）
- 技能：`payment-domain` `architecture` `observability`（见 `.claude/skills/`）
- Spec Kit（SDD）：`/speckit-specify` `/speckit-plan` `/speckit-tasks` `/speckit-implement` 等（见 `.claude/skills/speckit-*`）
