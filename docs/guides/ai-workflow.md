# AI Engineering Workflow（Spec-Driven Development）

> 对应交付物 C。定义 AI Agent（及人类）在本项目的工作方式：**先 Spec，后代码**。每个特性都走同一条流水线，保证可复现、可评审、可回检。

## 核心原则

**Spec 是需求的单一事实源（Single Source of Truth）。** 任何功能在写代码前必须先有 Spec；没有 Spec 不写实现。

## 流水线（每个特性一条）

```
Spec（写清楚"要什么"和"为什么"）
  → Plan（怎么拆、涉及哪些服务、依赖顺序、风险）
    → Task（可执行的最小任务清单，粒度=一个可独立验证的提交）
      → Implement（逐任务实现 + 测试）
        → Review（对照 Spec 回检缺口，补 ADR/文档）
```

Feature 开发的唯一入口是 Spec Kit；其他命令和 Skill 只能作为 Spec Kit 阶段中的辅助检查，不另起一套开发流程。

## 各阶段产出与门禁（Gate）

| 阶段 | 产出物 | 位置 | 门禁（进入下一阶段前） |
|---|---|---|---|
| **Spec** | 需求、边界、场景、验收与非功能要求 | `docs/specs/<feature>/spec.md` | 需求明确；涉及人类决策边界时确认 |
| **Plan** | 技术上下文、研究、数据模型、契约、快速验证 | `docs/specs/<feature>/plan.md` 及其附件 | 架构与依赖不越界 |
| **Task** | 按用户故事组织的可执行任务 | `docs/specs/<feature>/tasks.md` | 每项有 ID、路径和验收方式 |
| **Implement** | 代码 + 测试 + 迁移脚本 | 对应服务模块 | 编译通过 + lint 通过 + 测试通过 |
| **Review** | 对照 Spec 的回检结论 + 缺口清单 | 记录在 Plan / ADR | 缺口闭合或显式记录为后续阶段 |

## Spec Kit 已经替你完成什么

Spec Kit 会创建特性目录、复制模板、生成质量清单，并在规划阶段生成 `research.md`、`data-model.md`、`contracts/`、`quickstart.md` 等设计产物；`/speckit-tasks` 会生成带依赖和文件路径的任务清单。你不需要再手写一份平行的计划、任务表或额外开发流程。

你仍然必须负责：需求取舍、业务边界、支付状态机、数据所有权、重要架构决策、验收判断和对生成结果的审阅。Spec Kit 是流程工具，不是架构决策者。

## AI Agent 角色与约束

AI Agent 在流水线中的职责：

1. **写 Spec**：把需求转为结构化 Spec，**需求不明确时先澄清**（Constitution §7.8）。
2. **拆 Plan/Task**：先读现有代码与 Spec/ADR，再拆解（Constitution §7.9）。
3. **实现**：逐 Task 落地，**一次改动只做一件事**（Constitution §7.2）。
4. **测试 + Review**：实现 MUST 附带测试，并对照 Spec 回检（Constitution §7.10）。

**禁止**（来自 Constitution §7，此处重申为工作流硬约束）：
- 绕过架构规则、跨服务直接改他服务数据。
- 删测试或改测试来「通过」错误实现。
- 擅自改领域模型 / 状态机 / 服务边界（须先提方案并获人类确认）。

## 实现前分析（由命令吸收，不再单独写流水账）

收到非简单需求后，分析必须发生，但不要求另写一份重复文档。由以下产物承载：

1. `/speckit-specify`：需求、场景、边界、验收；
2. `/speckit-plan`：现状检查、技术选择、影响范围、数据模型、契约和验证方式；
3. `/speckit-tasks`：把实现拆成可验证任务；
4. 资金特性额外加载 `payment-domain`，涉及架构或日志指标时加载对应 skill。

## 与文档体系的关系

- **Constitution** 是最高约束，流水线每一步都必须遵守。
- **ADR** 记录架构决策，Spec 引用 ADR 作为「为什么这么定」的依据。
- **Spec** 描述「要什么」，**ADR** 描述「怎么选」，**Plan/Task** 描述「怎么做」。

## Feature 完成标准

一个 Feature 不是「代码能运行」就算完成。实现阶段至少检查：业务逻辑、状态机、异常处理、幂等性、一致性、测试、日志、Metrics、文档、数据库变更和部署影响；Trace、Alert、回滚方案按该特性风险纳入 Plan。存在未解决问题 MUST 明确说明。

## 触发场景

- 新领域或新能力 → 通过 Spec Kit 创建 Feature Spec；领域不是 Spec 目录的唯一粒度。
- 既有能力变更 → 更新对应 Feature Spec，或创建新的变更 Feature，再走 Plan → Task → Implement。
- 重大架构变化 → 先立 ADR（Constitution §7.7），再更新受影响 Spec。
