# Commerce & Payment Platform

面向生产环境的 Commerce & Payment Platform（Java / Spring Cloud 微服务），用于学习并实践支付、交易、履约、权益、对账、结算体系与高质量后端工程。不是 CRUD Demo。

> 本文是给 AI 编码代理的**项目导航与硬规则摘要**，遵循 AGENTS.md 跨工具标准（Claude Code / WorkBuddy / Cursor 等通用）。Feature 开发的唯一流程入口是 Spec Kit，详细约束见 `docs/` 与 `.specify/`。

## 必读文档（按需加载，不要凭假设动手）

| 文档 | 内容 | 何时读 |
|---|---|---|
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | 最高宪法（v2.3.0）：领域边界、架构、一致性、工程、可观测、AI 原则、人类决策边界、提交与合并节奏 | 涉及的工程 / 架构 / 业务约束 |
| [docs/README.md](docs/README.md) | 文档体系导航（分类目录、权威层级、路径收口） | 找文档时 |
| [docs/adr/](docs/adr/) | 架构决策记录（索引见 [docs/adr/README.md](docs/adr/README.md)，ADR 落点追溯见 [docs/adr/traceability.md](docs/adr/traceability.md)） | 需要历史决策原因、架构迁移或用户明确要求时 |
| [docs/architecture/technical-solution.md](docs/architecture/technical-solution.md) | 总体技术方案（**当前系统现状**：定位 / 架构 / 流程 / 非功能 / 部署 / 风险） | 涉及服务、端口、Schema、RPC 或部署 |
| [docs/architecture/systems/](docs/architecture/systems/) | 每服务系统设计文档（DDD 数据模型、API 契约、流程链路、存储缓存、部署拓扑） | 深入某一服务实现细节 |
| [docs/architecture/roadmap.md](docs/architecture/roadmap.md) | 项目阶段、当前状态、计划与下一 Feature | Feature 规划或里程碑任务 |
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

## Documentation Layers

### L0 — Current System Facts

- `docs/architecture/technical-solution.md`
- `docs/architecture/systems/*.md`

描述当前实际存在的架构、模块、职责、数据流、状态、接口和运行方式，是普通代码任务的默认系统事实来源。

### L1 — Current Constraints & Engineering Rules

- `.specify/memory/constitution.md`
- `docs/guides/*.md`

描述当前必须遵守的工程、业务和 AI 开发规则。只读取与当前任务直接相关的约束，不默认读取全部 L1 文档。

### L2 — Active Feature Work

- Stage 分组：`docs/specs/<stage>/`，索引见 `docs/specs/README.md`
- Feature：`docs/specs/<stage>/<feature>/spec.md` / `plan.md` / `tasks.md`

仅当任务明确属于该 Active Feature 时读取对应 Feature 文档。代码或架构文档中的 Feature 编号不会自动触发整套 Spec 读取。

### L3 — Historical / On-Demand

- `docs/adr/*.md`
- `docs/archive/audits/*.md`
- `docs/archive/design/*`（已完成阶段的 stage-design 归档）
- 已完成且已被当前架构吸收的 Feature Spec

这些文档默认不是普通代码任务的上下文，只在历史调查、架构迁移、Knowledge Promotion、当前实现与历史决策冲突或用户明确要求时按需读取。

生命周期含义如下：`Active` 表示仍直接指导实现；`Implemented but not promoted` 表示代码已实现但当前架构尚未完整吸收；`Implemented and promoted` 表示当前事实已进入 L0；`Historical` 表示仅记录过去；`Superseded` 表示已被替代；`Rejected` 表示已否决；`Human Review Required` 表示状态需要人工确认。Phase 2 只定义含义，不批量修改 ADR / Spec 状态。

## AI Context Loading Policy

核心规则：**Read the minimum sufficient context required for the task.** 采用 `Search → Locate → Read Relevant Section`，禁止 `List → Read Everything`，也禁止为了“完整理解项目”递归读取整个 `docs/`。

### 普通代码任务

默认读取：

1. `AGENTS.md`
2. 任务直接相关的 L0 System Design
3. 必要的 `technical-solution.md` 章节
4. 直接相关代码与测试

必要时再读取 Constitution、engineering-standards 或 business-standards 中直接相关的章节。默认不读取所有 ADR、所有 Spec、历史审计、Roadmap 或无关服务文档。

### Active Feature 任务

在上述基础上读取当前 Feature 的 `spec.md`、`plan.md`、`tasks.md`，以及直接相关 ADR。读取 Feature 不等于自动读取其引用的全部历史文档。

### Architecture 任务

读取 `AGENTS.md`、Constitution / 相关规范、`technical-solution.md`、相关 `systems/*.md` 与相关代码。仅当当前架构不足以解释设计、存在冲突、正在迁移或进行 Knowledge Promotion 时继续读取相关 ADR / Spec。

### Historical Investigation

只有在调查“为什么这么设计”、决策取舍、否决方案、历史差异、ADR 是否过时、Feature 完成时间、恢复历史背景或执行 Documentation Audit / Knowledge Promotion 时主动进入 ADR / Archive；仍只读取相关文档。

### Reference ≠ Dependency

文档引用只是导航信息，不自动形成 AI Context Dependency。读取 System Design 后，如果当前任务已能完成，必须停止；只有当前信息不足或存在明确冲突时，才读取被引用的 ADR / Spec。禁止沿引用链递归扩展上下文。

### Context Stop Rules

满足以下条件后停止继续读取文档：已找到当前任务所需的系统事实、相关代码、必要约束，且没有未解决的架构冲突或必须调查的历史原因。禁止为了增加 confidence 无边界读取历史文档。

### Documentation Drift

发现 `Code ≠ System Design`、`System Design ≠ Technical Solution`、`Current Architecture ≠ Active Spec` 或 `Active Spec ≠ Active ADR` 时，不得静默选择或自行改写架构文档。必须报告 `DOCUMENTATION_DRIFT`，列出 Evidence、当前代码行为、各层文档描述、历史文档描述（如已读取）和需要人工确认的问题。

## 工作方式

1. **先 Spec 后代码**：非简单任务禁止直接写码。先按上方 AI Context Loading Policy 定位并读取最小充分上下文；只有任务明确属于 Active Feature 或确需历史原因时才读取相关 Spec / ADR。
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
