# AI 流程规范（AI Standards — Spec-Driven Development）

> 定义 AI Agent（及人类）在本项目的工作方式：**先 Spec，后代码**。每个特性都走同一条流水线，保证可复现、可评审、可回检。
>
> **权威层级**：本规范不得与 [Constitution](../../.specify/memory/constitution.md) 冲突，冲突时以宪法为准。技术侧约束见 [engineering-standards.md](engineering-standards.md)，业务侧约束见 [business-standards.md](business-standards.md)。

## 1. 核心原则

**Spec 是需求的单一事实源（Single Source of Truth）。** 任何功能在写代码前必须先有 Spec；没有 Spec 不写实现。

## 2. 流水线（每个特性一条）

```
Spec（写清楚"要什么"和"为什么"）
  → Plan（怎么拆、涉及哪些服务、依赖顺序、风险）
    → Task（可执行的最小任务清单，粒度 = 一个可独立验证的提交）
      → Branch（创建 feature/<NNN>-<slug> 分支；纯文档改动可豁免）
      → Implement（逐任务实现 + 测试，全部提交在 feature 分支上）
        → Review（对照 Spec 回检缺口，补 ADR/文档）
          → PR & Merge（推送分支 → 开 PR 引用 Spec → 自合并（--no-ff）回 master）
```

Feature 开发的唯一入口是 Spec Kit；其他命令和技能只能作为 Spec Kit 阶段中的辅助检查，不另起一套开发流程。

## 3. 一次特性如何完成（日常入口）

### 3.0 先判断是不是 Feature

- 新增业务能力、接口、状态、表或跨服务协作：**是 Feature**，必须走 Spec Kit。
- 修复一个明确的局部缺陷：可直接修复，但仍需先读相关 Spec、测试和代码。
- 改变服务边界、支付状态机、关键资金表、已发布 API 或新增中间件：**先写方案并等待人类确认**（宪法 Governance 人类决策边界）。

### 3.1 创建并确认需求

```text
/speckit-specify <用业务语言描述目标、用户、场景和约束>
```

检查 `docs/specs/<stage>/<feature>/spec.md`：范围是否小而明确，正常流、失败流、重复请求、超时和未知状态是否有验收标准。需要讨论的业务选择先解决，再进入设计。

### 3.2 形成技术设计

```text
/speckit-clarify       # Spec 有关键歧义时才用
/speckit-plan
/speckit-analyze       # 需要跨文档一致性检查时用
```

Plan 阶段确认服务边界、端口、Schema 数据所有权、同步 RPC、状态变化、一致性、依赖、可观测和验证路径。重大决策另写 `docs/adr/`，不要把 ADR 内容重复塞进 Spec。

### 3.3 生成任务并实现

```text
/speckit-tasks
/speckit-implement
```

先完成基础设施任务，再按用户故事逐步实现。每个任务都要能独立验证；测试、迁移、接口契约和文档不能被当成"以后再补"。

### 3.4 回检与收尾

```text
/review
/payment-review       # 触及支付、退款、账本、对账、结算时
```

对照 Spec 验收，确认测试和 `quickstart.md` 可执行；未完成内容写入 Feature 文档或后续任务。不要用修改测试断言的方式掩盖实现问题。

## 4. 各阶段产出与门禁（Gate）

| 阶段 | 产出物 | 位置 | 门禁（进入下一阶段前） |
|---|---|---|---|
| **Spec** | 需求、边界、场景、验收与非功能要求 | `docs/specs/<stage>/<feature>/spec.md` | 需求明确；涉及人类决策边界时确认 |
| **Plan** | 技术上下文、研究、数据模型、契约、快速验证 | `docs/specs/<stage>/<feature>/plan.md` 及其附件 | 架构与依赖不越界 |
| **Task** | 按用户故事组织的可执行任务 | `docs/specs/<stage>/<feature>/tasks.md` | 每项有 ID、路径和验收方式 |
| **Acceptance** | 如何判定完成（INV 门禁 + 演示验收 + 已知限制） | `docs/specs/<stage>/<feature>/acceptance.md` | 验收可复现、缺口显式登记 |
| **Implement** | 代码 + 测试 + 迁移脚本 | 对应服务模块 | 编译通过 + 测试通过 |
| **Review** | 对照 Spec 的回检结论 + 缺口清单 | 记录在 Plan / ADR | 缺口闭合或显式记录为后续阶段 |

> 上述 `spec.md` / `plan.md` / `tasks.md` / `acceptance.md` 四件即为 **Spec Kit 四件套**。文档类型、骨架与 Review 规则见 `docs/standards/`。

**合并回 master 前 MUST 满足**宪法 Governance §提交与合并节奏的四条前置校验：① 全量 `mvn clean verify` 通过；② Spec 验收清单勾选；③ ADR 状态已决；④ 文档无漂移（判定依据见 [engineering-standards.md](engineering-standards.md) §11 文档漂移检查清单）。

## 5. Spec Kit 已经替你完成什么（不用重复手写）

Spec Kit 会创建特性目录、复制模板、生成质量清单，并在规划阶段生成 `research.md`、`data-model.md`、`contracts/`、`quickstart.md` 等设计产物；`/speckit-tasks` 会生成带依赖和文件路径的任务清单。**你不需要再手写一份平行的计划、任务表或额外开发流程**，项目文档也不再维护 `docs/plans/` 或另一份任务表。

你仍然必须负责：需求取舍、业务边界、支付状态机、数据所有权、重要架构决策、验收判断和对生成结果的审阅。Spec Kit 是流程工具，不是架构决策者。

## 6. AI Agent 角色与约束

AI Agent 在流水线中的职责：

1. **写 Spec**：把需求转为结构化 Spec，**需求不明确时先澄清**。
2. **拆 Plan/Task**：先读现有代码与 Spec/ADR，再拆解。
3. **实现**：逐 Task 落地，**一次改动只做一件事**。
4. **测试 + Review**：实现 MUST 附带测试，并对照 Spec 回检。

**禁止**：

- 绕过架构规则、跨服务直接改他服务数据。
- 删测试或改测试来「通过」错误实现。
- 擅自改领域模型 / 状态机 / 服务边界（须先提方案并获人类确认）。
- **在 `master` 上直接提交代码改动**：非 docs-only 任务 MUST 先创建 feature 分支（`feature/<NNN>-<slug>` / `fix/<slug>` / `chore/<slug>`），通过 PR 合并回 master。

> 完整红线清单见根 [AGENTS.md](../../AGENTS.md)「硬性红线」；业务侧禁止项见 [business-standards.md](business-standards.md)。

## 7. 实现前分析（由命令吸收，不再单独写流水账）

收到非简单需求后，分析必须发生，但不要求另写一份重复文档。由以下产物承载：

1. `/speckit-specify`：需求、场景、边界、验收；
2. `/speckit-plan`：现状检查、技术选择、影响范围、数据模型、契约和验证方式；
3. `/speckit-tasks`：把实现拆成可验证任务；
4. 资金特性额外加载 `payment-domain`，涉及架构或日志指标时加载对应 skill。

## 8. Feature 完成标准

一个 Feature 不是「代码能运行」就算完成。实现阶段至少检查：业务逻辑、状态机、异常处理、幂等性、一致性、测试、日志、Metrics、文档、数据库变更和部署影响；Trace、Alert、回滚方案按该特性风险纳入 Plan。存在未解决问题 MUST 明确说明。

## 9. 触发场景

- 新领域或新能力 → 通过 Spec Kit 创建 Feature Spec；领域不是 Spec 目录的唯一粒度。
- 既有能力变更 → 更新对应 Feature Spec，或创建新的变更 Feature，再走 Plan → Task → Implement。
- 重大架构变化 → 先立 ADR，再更新受影响 Spec。

## 10. 与文档体系的关系

- **Constitution** 是最高约束，流水线每一步都必须遵守。
- **ADR** 记录架构决策，Spec 引用 ADR 作为「为什么这么定」的依据。
- **Spec** 描述「要什么」，**ADR** 描述「怎么选」，**Plan/Task** 描述「怎么做」。
- 三规范分工：本文件 = **AI 流程规范**（怎么开发）；[engineering-standards.md](engineering-standards.md) = **技术流程规范**（怎么写）；[business-standards.md](business-standards.md) = **业务流程规范**（业务上必须守什么）。

## 11. AI Context Loading Policy

### 11.1 文档层级

- **L0 Current System Facts**：`docs/architecture/technical-solution.md` 与 `docs/architecture/systems/*.md`，描述当前系统事实。
- **L1 Current Constraints & Engineering Rules**：Constitution 与 `docs/guides/*.md`，按任务读取相关约束。
- **L2 Active Feature Work**：当前 Feature 的 `spec.md`、`plan.md`、`tasks.md`，仅在任务明确属于该 Feature 时读取。
- **L3 Historical / On-Demand**：`docs/adr/*.md`、`docs/archive/audits/*.md` 与已完成且已吸收的 Feature Spec，默认不读取。

生命周期状态仅表示：`Active`、`Implemented but not promoted`、`Implemented and promoted`、`Historical`、`Superseded`、`Rejected`、`Human Review Required`。本阶段不批量变更任何 ADR / Spec 的状态。

### 11.2 按任务读取

普通代码任务采用：`AGENTS.md → 相关 System Design → 必要的 Technical Solution 章节 → 相关代码与测试`。必要时补充相关 L1 规则；不默认读取 ADR、全部 Spec、历史审计、Roadmap 或无关服务文档。

Active Feature 任务在此基础上读取该 Feature 的 Spec / Plan / Tasks，以及直接相关 ADR。Architecture Task 读取 Constitution / 相关规范、Technical Solution、相关 System Design 与代码；只有当前材料不足、存在冲突、正在迁移或进行 Knowledge Promotion 时才进入 ADR / Spec。Historical Investigation 才主动调查 ADR / Archive，且只读取相关文档。

### 11.3 Reference ≠ Dependency

文档引用关系只是导航信息，不自动形成 AI Context Dependency。读取一个文档后，若当前任务已经能够完成，必须停止；不得因为引用了 ADR 而自动读取 ADR，也不得沿 ADR → Spec → 其他 ADR 的链条递归加载整个 docs/。

统一采用：

`Search → Locate → Read Relevant Section`

而不是：

`List → Read Everything`

### 11.4 停止与漂移处理

满足“已找到当前系统事实、相关代码、必要约束，且没有未解决架构冲突或必须调查的历史原因”后停止读取。发现代码、System Design、Technical Solution、Active Spec 或 Active ADR 之间冲突时，必须输出 `DOCUMENTATION_DRIFT`，说明证据、当前代码行为、各文档描述和需要人工确认的问题；不得静默选择优先级，也不得借机修改架构文档。代码与当前系统事实的优先级仅用于报告证据顺序：当前代码 / 测试 → System Design → Technical Solution → Active Spec → Active ADR → Historical ADR / Completed Spec → Archive。
