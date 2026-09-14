# Commerce & Payment Platform

面向生产环境的 Commerce & Payment Platform（Java / Spring Cloud 微服务），用于学习并实践支付、交易、履约、权益、对账、结算体系与高质量后端工程。不是 CRUD Demo。

> 本文是给 AI 编码代理的**项目导航与硬规则摘要**，遵循 AGENTS.md 跨工具标准（Claude Code / WorkBuddy / Cursor 等通用）。Feature 开发的唯一流程入口是 Spec Kit，详细约束见 `docs/` 与 `.specify/`。

## 必读文档（按需加载，不要凭假设动手）

| 文档 | 内容 | 何时读 |
|---|---|---|
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | 最高宪法（v2.3.0）：领域边界、架构、一致性、工程、可观测、AI 原则、人类决策边界、提交与合并节奏 | 任何实现 / 设计前 |
| [docs/README.md](docs/README.md) | 文档体系导航（分类目录、权威层级、路径收口） | 找文档时 |
| [docs/adr/](docs/adr/) | 架构决策记录（索引见 [docs/adr/README.md](docs/adr/README.md)，ADR 落点追溯见 [docs/adr/traceability.md](docs/adr/traceability.md)） | 涉及架构 / 技术选型 |
| [docs/architecture/technical-solution.md](docs/architecture/technical-solution.md) | 总体技术方案（**当前系统现状**：定位 / 架构 / 流程 / 非功能 / 部署 / 风险） | 涉及服务、端口、Schema、RPC 或部署 |
| [docs/architecture/systems/](docs/architecture/systems/) | 每服务系统设计文档（DDD 数据模型、API 契约、流程链路、存储缓存、部署拓扑） | 深入某一服务实现细节 |
| [docs/architecture/roadmap.md](docs/architecture/roadmap.md) | 项目阶段、当前状态、计划与下一 Feature | 开始或完成一个 Feature 前 |
| [docs/guides/ai-standards.md](docs/guides/ai-standards.md) | **AI 流程规范**：Spec Kit 流水线、阶段门禁、Agent 角色与约束 | 开始一个特性前 |
| [docs/guides/engineering-standards.md](docs/guides/engineering-standards.md) | **技术流程规范**：编码 / 测试 / CI / 可观测 / 安全 / 依赖管理 | 写代码 / 测试 / 配置前 |
| [docs/guides/business-standards.md](docs/guides/business-standards.md) | **业务流程规范**：领域边界 / 资金正确性 / 状态机 / 一致性 | 触及资金或跨域流程前 |

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
4. **涉及人类决策边界必须暂停**：说明原因 / 影响 / 方案 A / B / 推荐 / 风险，等人类确认（边界清单见宪法 Governance）。

## 目录与产物纪律

- **测试 / 演示 / 压测组件收口在 `deployment/`**：`architecture-tests`、`mock-channel-web`、`demo`、`performance` 均属非领域组件，MUST 放在 `deployment/` 下；仓库根目录只允许放 Maven 多模块工程的服务与 common 模块。
- **构建 / 运行产物不准污染根目录**：Maven 输出、性能测试报告（`perf-report.html`、`load-result.json`）、k6 脚本运行产物等 MUST 落在 `deployment/output/`（已被 gitignore）或系统临时目录，严禁写入仓库根目录。
- **提交节奏与分支纪律见宪法**：非 docs-only 的代码改动 MUST 在 feature 分支开发（命名 `feature/<NNN>-<slug>` / `fix/<slug>` / `chore/<slug>`），推送后走 PR 合并回 master（merge commit，--no-ff）；每个 Spec 完成 MUST 立即 PR 合并，不允许跨 Spec 长期堆积改动。禁止在 `master` 上直接提交代码改动（纯文档 `docs:` / CI 杂务 `chore:` 例外）。细则见 `docs/guides/engineering-standards.md` §6 与 `.specify/memory/constitution.md` Governance §提交与合并节奏。
- **Spec / 纯文档编写用 worktree 隔离**：主工作区有 feature WIP 时，新 Spec MUST 经 `./spec-worktree.sh new <NNN>-<slug>` 在独立 worktree 编写，`push` 子命令（docs-only 校验 + 直推 master）收口、`rm` 清理，不触碰主工作区（细则见 `docs/guides/engineering-standards.md` §6）。

## 命令与技能

- **Spec Kit（唯一 Feature 流程入口）**：`/speckit-specify` `/speckit-clarify` `/speckit-plan` `/speckit-tasks` `/speckit-implement` 等。技能定义在 `.claude/skills/speckit-*`；Claude Code 下以 `/speckit-*` 调用，其他工具按同名技能加载。
- **辅助检查**：`/review` `/payment-review` `/test`（`.claude/commands/`）。
- **项目技能**：`payment-domain` `architecture` `observability`（`.claude/skills/`）。
