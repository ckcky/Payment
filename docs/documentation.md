# 文档体系（Documentation System）

> 对应交付物 E。定义本项目文档的种类、位置、职责与维护规则，保证「文档即单一事实源」，代码与文档不脱节。

## 文档清单与职责

| 文档 | 位置 | 职责 | 维护时机 |
|---|---|---|---|
| **Constitution** | `.specify/memory/constitution.md` | 最高工程与架构约束（spec-kit 权威位置，v2.0.0） | 架构级变化时（走宪法修订流程） |
| **CLAUDE.md** | 根目录 | Claude Code 自动加载的项目地图与指针 | 架构 / 文档路径变化时 |
| **ADR** | `docs/adr/NNNN-*.md` | 记录不可逆/重要架构决策 | 每次重要决策时 |
| **Spec** | `specs/<feature>/spec.md` | 特性的需求 + 边界 + 验收（单一事实源） | 特性新增或变更时 |
| **Plan / Task** | `specs/<feature>/plan.md` + `tasks.md` | 技术方案与任务拆解（spec-kit 产出） | 每个特性开工前 |
| **工程规范** | `docs/engineering-standards.md` | 编码/测试/CI 的具体约束 | 规范调整时 |
| **工作流** | `docs/ai-workflow.md` | SDD 流程补充（配合 spec-kit 命令） | 流程调整时 |
| **目录结构** | `docs/project-structure.md` | 项目骨架约定 | 模块增删时 |
| **README** | 根目录 | 项目目标、架构总览、快速开始 | 保持最新 |

## 层级与优先级

```
Constitution（.specify/memory/constitution.md，最高宪法）
   ├── ADR（docs/adr/，记录重要决策，不自动取代宪法）
   └── Feature（specs/<feature>/）
          ├── spec.md（要什么）
          ├── plan.md（怎么设计）
          ├── tasks.md（怎么执行）
          └── 代码 / 测试（实现结果）
```

冲突时按 Constitution 的优先级处理。ADR 不能自行取代宪法；若决策改变宪法原则，必须先修订 Constitution，再更新 ADR。

## 工作流（Spec Kit）

采用 GitHub Spec Kit 走 Spec-Driven Development，命令定义在 `.claude/skills/`：

`/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`
（可选 `/speckit-clarify`、`/speckit-checklist`、`/speckit-analyze`、`/speckit-converge`）。

宪法更新用 `/speckit-constitution`（写入 `.specify/memory/constitution.md`）。

## 文档规则

1. **单一事实源**：一个需求只在 Spec 定义，不在代码注释里另起炉灶；代码与 Spec 不一致时，先判断是需求变更还是实现缺陷，再同步修订。
2. **ADR 编号**：顺序递增 `0001`、`0002`…，用英文短横线命名。
3. **Spec 布局**：spec-kit 的 `specs/<feature>/`（spec.md + plan.md + tasks.md），特性目录由 `/speckit-specify` 生成。
4. **写文档的时机**：决策当场写 ADR，需求澄清当场写 Spec，不事后补记。
5. **文档也走 Review**：ADR / Spec 变更同样需要人类确认（涉及宪法「人类决策边界」时 MUST）。

## 当前已有文档

- `.specify/memory/constitution.md`（v2.0.0）— 唯一宪法（微服务 + Java），已取代原 `docs/constitution.md`。
- `CLAUDE.md`（根目录）— 项目地图 + 指针。
- `.specify/`（spec-kit 结构：memory / scripts / templates / workflows）+ `.claude/skills/speckit-*`（SDD 命令）。
- `docs/adr/0001-adopt-spring-cloud-microservices.md`
- `docs/adr/0002-technology-stack.md`
- `docs/project-structure.md`
- `docs/ai-workflow.md`
- `docs/engineering-standards.md`
- 本文档 `docs/documentation.md`
