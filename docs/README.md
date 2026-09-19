# 文档体系（Documentation System）

> 定义本项目文档的种类、位置、职责与维护规则，保证「文档即单一事实源」，代码与文档不脱节。

## 目录导航

| 目录 | 内容 | 类型 |
|---|---|---|
| [architecture/](architecture/) | 总体技术方案、每服务系统设计（[systems/](architecture/systems/)）、模块结构、Roadmap | Explanation（是什么/为什么） |
| [adr/](adr/) | 架构决策记录（ADR）及索引 | Reference + 生命周期 |
| [guides/](guides/) | 三规范：AI 流程规范 / 技术流程规范 / 业务流程规范 | Reference + How-to（怎么做） |
| [deployment/](../deployment/) | 本地/Compose 运行说明 | How-to |
| [operations/](operations/) | 运维手册（[runbook.md](operations/runbook.md)：启停/巡检/故障处理） | How-to |
| [archive/audits/](archive/audits/) | 历史审计报告（已归档，标注 Status，不再作为权威事实源） | 历史留档 |
| [specs/](specs/) | Feature 文档（Spec/Plan/Tasks，唯一目录） | Feature 生命周期产物 |

## 文档清单与职责

| 文档 | 位置 | 职责 | 维护时机 |
|---|---|---|---|
| **Constitution** | `.specify/memory/constitution.md` | 最高工程与架构约束（spec-kit 权威位置，v2.3.0） | 架构级变化时（走宪法修订流程） |
| **AGENTS.md** | 根目录 | AI 编码代理的项目地图与硬规则摘要（跨工具标准；`CLAUDE.md` 为兼容 Claude Code 的指针） | 架构 / 文档路径变化时 |
| **ADR** | `docs/adr/NNNN-*.md`（索引见 [docs/adr/README.md](adr/README.md)，落点见 [traceability.md](adr/traceability.md)） | 记录不可逆/重要架构决策 | 每次重要决策时 |
| **总体技术方案** | `docs/architecture/technical-solution.md` | 全局技术方案（**只描述系统现状**：背景 / 目标 / 架构 / 流程 / 非功能 / 部署 / 风险） | 架构基线变化时 |
| **系统设计文档** | `docs/architecture/systems/<service>-service.md`（9 篇） | 每服务系统设计：DDD 数据模型、API 契约、流程链路、存储缓存、部署拓扑 | 服务实现细节变化时 |
| **Roadmap** | `docs/architecture/roadmap.md` | 项目阶段、当前状态、计划与 Feature 依赖（计划类内容的唯一权威） | 阶段或里程碑变化时 |
| **AI 流程规范** | `docs/guides/ai-standards.md` | Spec Kit 流水线、阶段门禁、Agent 角色与约束 | 流程调整时 |
| **技术流程规范** | `docs/guides/engineering-standards.md` | 编码 / 测试 / CI / 可观测 / 安全 / 依赖管理 | 规范调整时 |
| **业务流程规范** | `docs/guides/business-standards.md` | 领域边界 / 资金正确性 / 状态机 / 一致性 | 业务规则调整时 |
| **部署说明** | `deployment/README.md` | 本地/Compose 启动最小 how-to | 运行方式变化时 |
| **Feature Spec** | `docs/specs/<feature>/spec.md` | 特性的需求、边界与验收（单一事实源） | 特性新增或变更时 |
| **README** | 根目录 | 项目目标、架构总览、快速开始 | 保持最新 |

## 层级与优先级

```
Constitution（.specify/memory/constitution.md，最高约束）
   ├── L0 Current System Facts
   │     ├── 总体技术方案（docs/architecture/technical-solution.md，当前有效基线）
   │     └── 每服务系统设计（docs/architecture/systems/<service>-service.md）
   ├── L1 Current Constraints & Engineering Rules
   │     └── docs/guides/*.md（按任务读取相关规范）
   ├── L2 Active Feature Work
   │     └── Feature（docs/specs/<feature>/，仅当前 Feature 默认读取）
   │           ├── spec.md（要什么）
   │           ├── plan.md（怎么设计）
   │           ├── tasks.md（怎么执行）
   │           └── 代码 / 测试（实现结果）
   └── L3 Historical / On-Demand
         └── ADR（docs/adr/）与 archive（按需读取）
```

普通代码任务默认从 L0 和相关代码 / 测试开始，按任务补充 L1；L2 仅用于明确的 Active Feature，L3 仅按需读取。文档引用是导航，不自动形成读取依赖。冲突时按 Constitution 的约束处理；若代码、当前架构和 Feature / ADR 之间存在事实冲突，必须报告 `DOCUMENTATION_DRIFT`，不得静默改写架构文档。

## 工作流（Spec Kit）

采用 GitHub Spec Kit 走 Spec-Driven Development，Spec Kit 是唯一 Feature 开发流程入口，命令定义在 `.claude/skills/`：

`/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`
（可选 `/speckit-clarify`、`/speckit-checklist`、`/speckit-analyze`、`/speckit-converge`）。

宪法更新用 `/speckit-constitution`（写入 `.specify/memory/constitution.md`）。

## 文档规则

1. **单一事实源**：一个需求只在 Spec 定义，不在代码注释里另起炉灶；代码与 Spec 不一致时，先判断是需求变更还是实现缺陷，再同步修订。
2. **ADR 编号**：顺序递增 `0001`、`0002`…，用英文短横线命名；状态机见 [docs/adr/README.md](adr/README.md)。
3. **Spec 布局**：Spec Kit 的 `docs/specs/<feature>/`（spec.md + plan.md + tasks.md），特性目录由 `/speckit-specify` 生成；路径统一为 `docs/specs/<feature>/`。
4. **写文档的时机**：决策当场写 ADR，需求澄清当场写 Spec，不事后补记。
5. **文档也走 Review**：ADR / Spec 变更同样需要人类确认（涉及宪法「人类决策边界」时 MUST）。
6. **一次性报告**：审计/调研等临时报告放 `docs/archive/audits/`，文件名带日期，并标注 `Status`（archived / superseded），不再作为权威事实源。
