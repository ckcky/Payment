# 文档体系（Documentation System）

> 定义本项目文档的种类、位置、职责与维护规则，保证「文档即单一事实源」，代码与文档不脱节。

## 目录导航

| 目录 | 内容 | 类型 |
|---|---|---|
| [architecture/](architecture/) | 总体技术方案、模块结构、Roadmap | Explanation（是什么/为什么） |
| [adr/](adr/) | 架构决策记录（ADR）及索引 | Reference + 生命周期 |
| [guides/](guides/) | 工程规范、开发指南、AI 工作流 | Reference + How-to（怎么做） |
| [deployment/](deployment/) | 本地/Compose 运行说明 | How-to |
| [audits/](audits/) | 一次性审计报告（带日期，已归档） | 历史留档 |
| [specs/](specs/) | Feature 文档（Spec/Plan/Tasks，唯一目录） | Feature 生命周期产物 |

## 文档清单与职责

| 文档 | 位置 | 职责 | 维护时机 |
|---|---|---|---|
| **Constitution** | `.specify/memory/constitution.md` | 最高工程与架构约束（spec-kit 权威位置，v2.0.0） | 架构级变化时（走宪法修订流程） |
| **CLAUDE.md** | 根目录 | Claude Code 自动加载的项目地图与指针 | 架构 / 文档路径变化时 |
| **ADR** | `docs/adr/NNNN-*.md`（索引见 [docs/adr/README.md](adr/README.md)） | 记录不可逆/重要架构决策 | 每次重要决策时 |
| **总体架构方案** | `docs/architecture/overview.md` | 当前有效运行形态、服务边界、Schema 和 RPC 规则 | 架构基线变化时 |
| **Roadmap** | `docs/architecture/roadmap.md` | 项目阶段、当前状态、Feature 依赖和下一步 | 阶段或里程碑变化时 |
| **目录结构** | `docs/architecture/project-structure.md` | 项目骨架约定 | 模块增删时 |
| **工程规范** | `docs/guides/engineering-standards.md` | 编码/测试/CI 的具体约束 | 规范调整时 |
| **开发入口** | `docs/guides/development-guide.md` | 从需求到交付的日常开发入口 | 流程调整时 |
| **AI 工作流** | `docs/guides/ai-workflow.md` | SDD 流程补充（配合 spec-kit 命令） | 流程调整时 |
| **部署说明** | `docs/deployment/README.md` | 本地/Compose 启动最小 how-to | 运行方式变化时 |
| **Feature Spec** | `docs/specs/<feature>/spec.md` | 特性的需求、边界与验收（单一事实源） | 特性新增或变更时 |
| **README** | 根目录 | 项目目标、架构总览、快速开始 | 保持最新 |

## 层级与优先级

```
Constitution（.specify/memory/constitution.md，最高宪法）
   ├── ADR（docs/adr/，记录重要决策，不自动取代宪法）
   ├── 总体架构方案（docs/architecture/overview.md，当前有效基线）
   ├── Roadmap（docs/architecture/roadmap.md，阶段边界）
   └── Feature（docs/specs/<feature>/）
          ├── spec.md（要什么）
          ├── plan.md（怎么设计）
          ├── tasks.md（怎么执行）
          └── 代码 / 测试（实现结果）
```

冲突时按 Constitution 的优先级处理。ADR 不能自行取代宪法；若决策改变宪法原则，必须先修订 Constitution，再更新 ADR。

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
6. **一次性报告**：审计/调研等临时报告放 `docs/audits/`，文件名带日期，并标注 `Status`（active / superseded）。
