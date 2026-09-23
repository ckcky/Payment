# 文档治理规范（Documentation Governance）

> **Status**: Active
> **Authority**: L1（Current Constraints & Engineering Rules）。不得与 [Constitution](../../.specify/memory/constitution.md) 冲突；冲突时以宪法为准。
> **Audience**: 人类开发者 + AI Agent。

本文件定义 PaymentArch 文档体系的**类型、职责、目录、命名、状态、生命周期与权威性**。它是所有其他 Standards 的母规范（`docs/standards/*.md` 各自细化一类文档的骨架，不得与本文件冲突）。

---

## 1. 治理模型

```
Constitution（最高约束）
    ↓
Standards（本目录：类型 / 职责 / 骨架 / 图规范 / Review）
    ↓
Templates（docs/templates/：可直接复制的空模板）
    ↓
Actual Docs（docs/** 的真实文档）
    ↓
Review（docs/standards/documentation-review.md + /doc-review）
    ↓
AI Agent / 人类开发者
    ↓
Implementation（代码 / 测试 / 迁移）
    ↓
Test / CI（构建 + 架构测试 + schema 门禁）
    ↓
Updated Current Facts（L0 回写）
```

核心原则：**人负责决策，文档负责定义，AI 负责执行，Review / CI 负责约束。**

---

## 2. 文档类型（Document Types）

正式定义 8 种文档类型。每种类型只回答一个问题：

| 类型 | 回答 | 落点 | 骨架 |
|---|---|---|---|
| **Requirement** | 为什么做 / 做什么？（业务意图，先于 Feature） | `docs/specs/<stage>/<feature>/spec.md` 的 §1 Problem / §2 Goal（不单独成文） | — |
| **Spec** | 这个 Feature 必须实现什么行为？ | `docs/specs/<stage>/<feature>/spec.md` | [spec-standard.md](spec-standard.md) |
| **Technical Solution** | 整个系统总体怎么组织？（全局快照） | `docs/architecture/technical-solution.md` | [technical-solution-standard.md](technical-solution-standard.md) |
| **System Design** | 某个具体服务 / 子系统怎么正确运行？ | `docs/architecture/systems/<service>-service.md` | [system-design-standard.md](system-design-standard.md) |
| **ADR** | 为什么选择这个架构决策？ | `docs/adr/NNNN-*.md` | [adr-standard.md](adr-standard.md) |
| **Plan** | 这次 Feature 怎么落地？ | `docs/specs/<stage>/<feature>/plan.md` | [spec-standard.md](spec-standard.md) §2 |
| **Tasks** | 具体改哪些东西？ | `docs/specs/<stage>/<feature>/tasks.md` | [spec-standard.md](spec-standard.md) §3 |
| **Runbook** | 系统出现问题以后怎么处理？ | `docs/operations/<topic>.md` | [../templates/runbook.md](../templates/runbook.md) |

**不属于上述 8 类的文档**（有各自的既有约定，不受本规范的骨架约束，但仍受 §5 状态约定与 §7 引用规则约束）：

- Constitution：`.specify/memory/constitution.md`
- Guides（工程 / AI / 业务流程规范）：`docs/guides/*.md`
- 导航索引：`docs/README.md`、`docs/specs/README.md`、`docs/adr/README.md`、`docs/adr/traceability.md`
- Feature 辅助产物：`acceptance.md`、`stage-design.md`、`design-review.md`、`design-summary.md`
- Roadmap（计划类内容唯一权威）：`docs/architecture/roadmap.md`
- 发版：`docs/releases/`
- 归档：`docs/archive/**`

---

## 3. 职责矩阵（Responsibility Matrix）

对每一种类型，固定以下八项。

### 3.1 Requirement

- **Purpose**：把业务意图（为什么做 / 做什么）写成可讨论的文字。
- **Scope**：单一需求主题的动机、收益、成功判据。
- **Must contain**：Problem、Goal（可并入 Spec 头部）。
- **Must not contain**：接口设计、数据模型、状态机、任务拆分。
- **Authority**：Spec 的输入，本身不是行为契约；一旦形成 Feature，**Spec 取代它**成为权威。
- **Inputs**：业务方诉求、路线图、审计缺口。
- **Outputs**：Spec 的 §1 / §2。
- **Related Documents**：Roadmap、Spec。

### 3.2 Spec

- **Purpose**：定义一个 Feature 的行为契约（要什么、不要什么、怎么判定完成）。
- **Scope**：**一个 Feature** 的行为边界；不下沉到跨服务全局，也不上浮到业务愿景。
- **Must contain**：见 [spec-standard.md](spec-standard.md) §1 的 13 个章节。
- **Must not contain**：架构决策的论证过程（属 ADR）、其他服务内部实现、其他 Feature 的设计。
- **Authority**：**该 Feature 行为的单一事实源**。代码与 Spec 不一致时，先判定是需求变更还是实现缺陷。
- **Inputs**：Requirement、Roadmap、相关 ADR、相关 System Design。
- **Outputs**：Plan、Tasks、Acceptance、代码与测试。
- **Related Documents**：ADR（为什么这么定）、System Design（落点）、Plan / Tasks / Acceptance。

### 3.3 Technical Solution

- **Purpose**：描述**当前系统整体**怎么组织（Current System Architecture Snapshot）。
- **Scope**：跨服务、全局视角。**只描述现状**。
- **Must contain**：见 [technical-solution-standard.md](technical-solution-standard.md) 的 12 章。
- **Must not contain**：详细字段定义、详细 DTO、大量接口定义、单个服务的内部实现、单个 Feature 的完整设计与详细状态机、重复 System Design 的内容。
- **Authority**：全局架构基线的唯一权威；凡跨服务事实冲突，以本文件为准（前提是它与 System Design 不冲突，冲突时按 §6 报 `DOCUMENTATION_DRIFT`）。
- **Inputs**：各 System Design、已 Accepted 的 ADR、已实现 Feature 的结论。
- **Outputs**：全局上下文，供 System Design 与 Spec 引用。
- **Related Documents**：System Design（细节下沉目标）、ADR（决策依据）、Roadmap（计划）。

### 3.4 System Design

- **Purpose**：描述**一个服务**怎么正确运行（数据模型 / 状态机 / 契约 / 链路 / 存储 / 部署）。
- **Scope**：一个服务或一个明确子系统。可含领域专项扩展（如 Ledger 的 Posting）。
- **Must contain**：见 [system-design-standard.md](system-design-standard.md) 的 15 章核心骨架。
- **Must not contain**：全局架构决策论证（属 ADR）、其他服务内部实现、其他 Feature 的行为契约。
- **Authority**：该服务实现细节的单一事实源。
- **Inputs**：Technical Solution、相关 ADR、相关 Spec。
- **Outputs**：服务级上下文，供 Spec / 实现 / 运维引用。
- **Related Documents**：Technical Solution、ADR、Spec、Runbook。

### 3.5 ADR

- **Purpose**：记录**一个重要且不可逆**的架构决策及其取舍。
- **Scope**：**一个决策**。不写实现细节、不写 Feature 行为契约。
- **Must contain**：见 [adr-standard.md](adr-standard.md) 的 7 段。
- **Must not contain**：多个独立决策混写、可逆的实现细节、Feature 的 FR 清单。
- **Authority**：决策「为什么」的唯一权威。Accepted ADR 不可变。
- **Inputs**：架构 / 业务约束、备选方案调研。
- **Outputs**：约束下游 Spec / System Design / Technical Solution。
- **Related Documents**：Spec（消费决策）、System Design（落点）、被 Supersede 的旧 ADR。

### 3.6 Plan

- **Purpose**：说明**这次 Feature 怎么落地**（技术上下文、影响范围、依赖顺序、风险、验证方式）。
- **Scope**：一个 Feature 的实现路径。
- **Must contain**：见 [spec-standard.md](spec-standard.md) §2。
- **Must not contain**：**业务需求定义**（Spec 的职责）、**架构决策**（ADR 的职责）。
- **Authority**：实现路径的唯一权威。
- **Inputs**：Spec、相关 System Design、相关 ADR。
- **Outputs**：Tasks。
- **Related Documents**：Spec、Tasks、ADR。

### 3.7 Tasks

- **Purpose**：把实现拆成**可独立验证的最小任务**。
- **Scope**：一个 Feature 的改动清单。
- **Must contain**：任务 ID、涉及文件路径、验收方式、对 FR 的追溯。
- **Must not contain**：**架构决策**（ADR 的职责）、**业务规则**（Spec 的职责）。
- **Authority**：执行清单的唯一权威。
- **Inputs**：Plan、Spec。
- **Outputs**：代码 / 测试 / 迁移提交。
- **Related Documents**：Plan、Spec、Acceptance。

### 3.8 Runbook

- **Purpose**：说明**系统出问题以后怎么处理**。
- **Scope**：运维操作（启停、巡检、故障处置、回滚）。
- **Must contain**：见 [../templates/runbook.md](../templates/runbook.md)。
- **Must not contain**：架构论证、业务规则。
- **Authority**：运维操作的唯一权威。
- **Inputs**：Technical Solution、System Design、部署脚本。
- **Outputs**：运维动作记录 / 故障复盘。
- **Related Documents**：Technical Solution、System Design、部署说明。

### 3.9 职责重叠禁令（谁不能重复定义谁）

| 内容 | 唯一定义处 | 其他文档只能 |
|---|---|---|
| Feature 行为 / FR / 验收标准 | Spec | 链接引用 |
| 全局架构 / 服务清单 / 跨服务流程 | Technical Solution | 链接引用 |
| 单服务表结构 / 状态机 / 端点契约 | System Design | 链接引用 |
| 决策的「为什么」与备选方案 | ADR | 链接引用 |
| 实现路径与任务拆分 | Plan / Tasks | 链接引用 |
| 运维处置步骤 | Runbook | 链接引用 |

> **禁止**在 Technical Solution 里复制 System Design 的字段清单；**禁止**在 Spec 里重述 ADR 的备选方案；**禁止**在 Tasks 里定义业务规则。发现重复即视为 §8 Review 的 FAIL 项。

---

## 4. 目录与命名约定

### 4.1 目录

| 目录 | 内容 |
|---|---|
| `docs/architecture/` | Technical Solution、roadmap、`systems/` |
| `docs/architecture/systems/` | 每服务 System Design |
| `docs/adr/` | ADR 正文 + `README.md`（索引）+ `traceability.md`（落点） |
| `docs/specs/<stage>/<feature>/` | Spec Kit 四件套（**唯一合法 Feature 路径**） |
| `docs/specs/<stage>/stage-design.md` | 阶段总目标书（可选） |
| `docs/standards/` | 本治理层（7 份） |
| `docs/templates/` | 空模板（5 份） |
| `docs/guides/` | 三份流程规范 |
| `docs/operations/` | Runbook 与运维 backlog |
| `docs/design/` | 演示/UI 设计系统 |
| `docs/releases/` | 发版记录 |
| `docs/archive/audits/<date>-<slug>/` | 一次性审计报告（不含生效决策） |
| `docs/archive/design/<date>-<stage-slug>/` | 已完成阶段的 stage-design 归档 |

### 4.2 命名

- ADR：`NNNN-<kebab-slug>.md`（**四位编号 + 英文短横线**）。**文件名前缀 = 该文件内第一个 ADR 的编号**。
- 系统设计：`<service>-service.md`（如 `payment-service.md`）。
- Feature 目录：`NNN-<kebab-slug>`（三位编号）；Stage 目录：`stage-NN-<kebab-slug>`。
- 架构图：`NN-<kebab-slug>.puml` + 同名 `.svg`（见 [diagram-standard.md](diagram-standard.md) §3）。
- 归档：`<YYYY-MM-DD>-<slug>/`。
- **禁止**自由命名（中文文件名、空格、大写驼峰用于文件名）。

### 4.3 Spec 路径（硬规则）

唯一合法路径是 **`docs/specs/<stage>/<feature>/`**。

- ✅ `docs/specs/stage-05-channel-and-finance-deepening/031-ledger-accounting-foundation/spec.md`
- ❌ `docs/specs/<feature>/`（缺 stage 层）
- ❌ `specs/<feature>/`（顶层，本仓库无此目录）

所有 AI Skill、Script、Template、Guide、README、ADR 引用 MUST 使用含 stage 的完整路径。相对链接从 feature 目录指向 `docs/adr/` 时为 `../../../adr/`。

---

## 5. Status 约定（Current / Proposed / Historical）

### 5.1 三层事实口径

| 口径 | 含义 | 写作要求 |
|---|---|---|
| **Current** | 当前系统**已经存在 / 已生效**的事实 | 直接陈述，不得用「计划」「将」等未来式 |
| **Proposed** | **尚未成为当前事实**的设计 | 文档头部 MUST 显式 `Status: Proposed`；正文一律不得写成现状 |
| **Historical** | 已被替代、废弃或仅供历史追溯 | 文档头部 MUST 标 `Status: Historical` / `Deprecated` / `Superseded` |

**硬规则**：任何 Current 文档禁止把 Proposed 写成当前事实。`technical-solution.md` 与 `systems/*.md` 是 Current 层，**不得**包含未生效设计（已发生的架构演进应改写为现状，未生效的只允许以「链接 + 明确 Proposed 标注」出现）。

### 5.2 各文档类型的 Status 词表

| 类型 | 允许的 Status |
|---|---|
| Technical Solution / System Design | `Active`（Current 快照，无需其他状态） |
| ADR | `Proposed` / `Accepted` / `Rejected` / `Superseded` / `Deprecated` / `Not Implemented`（见 [adr-standard.md](adr-standard.md)） |
| Spec | Feature Lifecycle（见 §5.3） |
| Plan / Tasks / Acceptance | 与所属 Spec 的 Lifecycle **保持一致**（禁止 Plan 与 Spec 状态互斥） |
| 审计报告 / 归档 | `archived` / `superseded` |
| Standards / Templates / Guides | `Active` |

### 5.3 Feature Lifecycle（统一 8 态）

| 状态 | 含义 | 判定依据 |
|---|---|---|
| `Draft` | 需求/设计正在编写，未送审 | 文档未完成 |
| `In Review` | 已成文，待负责人裁决 | 有明确待裁决项 |
| `Approved` | 已批准，尚未开工 | 负责人确认，tasks 未开始 |
| `In Development` | 正在实现 | tasks 部分完成 |
| `Implemented` | 实现完成并合入 master | tasks 全部完成 + 验收通过 + PR 已合并 |
| `Deprecated` | 能力仍存在但已不推荐 | 有替代方向但未落地 |
| `Superseded` | 被后续 Feature 取代 | 新 Feature 明确取代关系 |
| `Not Implemented` | 明确不做（方案保留） | 负责人裁决不做 |

**禁止**使用自由文本作为正式生命周期状态：~~设计完成~~、~~待评审~~、~~提案中~~、~~设计中~~、~~开发中~~、~~部分完成~~、~~已完成~~。旧状态按语义映射到上表（如「设计完成，待评审」→ `In Review`；「已实现」/「已合入」→ `Implemented`；「提案中」→ `Draft`）。

**一致性要求**：`spec.md` / `plan.md` / `tasks.md` / `acceptance.md` / `docs/specs/README.md` / `docs/architecture/roadmap.md` 对同一 Feature 的状态 MUST 一致。

**例外**：明确属于 design-only / specification-only 的 Feature（如 UI 设计规范）可声明 `delivery_mode: design-only`，此时**不强制**四件套齐全（见 [spec-standard.md](spec-standard.md) §5）。除此之外正常 Feature 默认必须具备四件套。

---

## 6. 引用规则与权威性

1. **单一事实源**：一个事实只在一个文档定义，其他文档**链接引用**，不复制。
2. **权威优先级**（冲突时，只用于判定「报告顺序」，不是静默改写许可）：当前代码 / 测试 → System Design → Technical Solution → Active Spec → Active ADR → Historical ADR / 已完成 Spec → Archive。
3. **相对链接深度必须实测**：从 `docs/{a}/{b}/x.md` 到 `docs/adr/y.md` 的深度不同（`docs/adr/` 与 `docs/specs/<stage>/<feature>/` 深度不同），写链接后 MUST 验证目标存在。
4. **Reference ≠ Dependency**：文档引用只是导航，不自动形成 AI Context Dependency；禁止沿引用链递归读取。
5. **ADR 引用**：引用 ADR 时 MUST 使用四位数编号（`ADR-0074`）；`ADR-0054` 因历史编号异常，引用时 MUST 同时给出**文件名 + 标题**消歧。
6. **Documentation Drift**：发现 `Code ≠ System Design` / `System Design ≠ Technical Solution` / `Current Architecture ≠ Active Spec` / `Active Spec ≠ Active ADR` 时，MUST 报告 `DOCUMENTATION_DRIFT`（证据 / 各层描述 / 待确认问题），**不得静默选择或自行改写架构文档**。

---

## 7. 生命周期与更新规则

1. **决策当场写 ADR**，需求澄清当场写 Spec，不事后补记。
2. **Accept 后不可变**：ADR 决策变化 → 新建 ADR + 双向 `Supersedes` / `Superseded by`。
3. **Feature 完成即回写**：`spec.md` 头部 Status → `Implemented`；`docs/specs/README.md` 与 `roadmap.md` 同步；README 中的状态描述 MUST 与该 Feature 实际状态一致。
4. **L0 回写**：Feature 落地后，其已生效事实 MUST 进入 `technical-solution.md` / `systems/*.md`，并更新 `docs/adr/traceability.md` 落点。
5. **归档**：一次性报告放 `docs/archive/audits/<date>-<slug>/`；阶段完成后 stage-design 归档到 `docs/archive/design/<date>-<stage-slug>/`。归档文档不再是权威事实源。
6. **本次不批量历史化**：`docs/specs/` 中 001~026 等已交付 Feature **不移动目录**；仅在生命周期字段中标注 `Implemented`（或 `Historical`）。批量归档属独立治理问题。

---

## 8. 与 Review 的闭环

任何新增 / 修改的文档，交付前 MUST 过 [documentation-review.md](documentation-review.md)：

```
文档类型判定 → 对应 Standard → 对应 Template → Review Checklist → PASS / WARN / FAIL
```

- 提供 skill：`.claude/skills/documentation-review/SKILL.md`
- 提供命令：`/doc-review <file>`（`.claude/commands/doc-review.md`）
- 自动化的机械检查项（链接、路径、状态一致、ADR 索引完整）见 Review 规范 §10。

---

## 9. 相关文档

- [technical-solution-standard.md](technical-solution-standard.md)
- [system-design-standard.md](system-design-standard.md)
- [spec-standard.md](spec-standard.md)
- [adr-standard.md](adr-standard.md)
- [diagram-standard.md](diagram-standard.md)
- [documentation-review.md](documentation-review.md)
- [../templates/](../templates/)（空模板）
- [../README.md](../README.md)（文档体系导航）
- [../../.specify/memory/constitution.md](../../.specify/memory/constitution.md)（最高约束）
