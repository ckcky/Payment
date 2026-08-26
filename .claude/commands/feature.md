---
description: 走 Spec-Driven Development 流水线实现一个特性（Spec → Plan → Task → Implement → Review）
argument-hint: <特性或领域名称>
---

# 特性开发流水线

按 `docs/ai-workflow.md` 定义的 SDD 流水线实现一个特性：**先 Spec，后代码**。没有 Spec 不写实现。

## 硬约束（Constitution §7）

1. 需求不明确 MUST 先澄清，不得臆测实现（尤其资金路径）。
2. 实现前 MUST 先读相关 Spec / ADR / 现有代码，不得基于假设重写。
3. 一次改动只做一件事，不得静默顺带改无关代码。
4. 不得绕过领域边界、依赖方向、数据所有权。
5. 涉及 Constitution §8（领域边界、状态机、schema 迁移、API 破坏性变更等）MUST 先提方案获人类确认。

## 流程

### 1. Spec
- 输出 `specs/<feature>/spec.md`：需求、边界、场景、验收和非功能要求。
- 引用相关 ADR 说明「为什么这么定」。
- 涉及 §8 决策时，向人类确认后才继续。

### 2. Plan
- 输出 `specs/<feature>/plan.md` 及 Spec Kit 设计附件：涉及服务、改动范围、依赖、风险和验证方式。
- 确认不越界、不引入未批准依赖。

### 3. Task
- 拆成可执行最小任务，粒度 = 一个可独立验证的提交。
- 每个 Task 有明确验收标准。

### 4. Implement
- 逐 Task 落地，遵守 `docs/engineering-standards.md`。
- 每个 Task 附带测试；编译 + lint + 测试通过才算完成。

### 5. Review
- 对照 Spec 回检缺口，补 ADR / 文档。
- 缺口闭合或显式记录为后续阶段。
