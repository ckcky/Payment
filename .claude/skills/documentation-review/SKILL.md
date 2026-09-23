---
name: documentation-review
description: 文档统一检查——判定文档类型、加载对应 Standard 与 Template、逐项跑 10 组检查、输出 PASS/WARN/FAIL + P0/P1/P2。任何新增或修改的文档交付前必读。
---

# 文档 Review（执行层）

> **权威清单**：[docs/standards/documentation-review.md](../../../docs/standards/documentation-review.md)。本技能是它的**执行说明**，检查项以该文件为准；两者不一致时以标准文件为准并**立即修正本技能**。
>
> **本技能不复制业务事实**。文档类型、骨架、状态词表、服务口径等一律**实时读取** `docs/standards/*.md`，禁止凭记忆作答。

## 何时使用

- 新增 / 修改任何 `docs/**` 文档后（Spec 四件套、Technical Solution、System Design、ADR、Runbook、Plan/Tasks）；
- 执行 `/doc-review <file>` 时；
- 合并前置检查、知识提升（Knowledge Promotion）、文档审计时。

## 执行流程

```
1. 判定文档类型（8 类之一）
2. 加载对应 Standard（docs/standards/）
3. 加载对应 Template（docs/templates/）
4. 逐项执行 10 组检查（含 §5 机械命令）
5. 输出结论
```

### 步骤 1 — 判定类型与标准映射

| 文档位置 | 类型 | Standard | Template |
|---|---|---|---|
| `docs/specs/<stage>/<feature>/spec.md` | Requirement / Spec | `spec-standard.md` | `templates/spec.md` |
| `…/plan.md` `…/tasks.md` `…/acceptance.md` | Plan / Tasks / Acceptance | `spec-standard.md` | `templates/spec.md` 附注 |
| `docs/architecture/technical-solution.md` | Technical Solution | `technical-solution-standard.md` | `templates/technical-solution.md` |
| `docs/architecture/systems/*.md` | System Design | `system-design-standard.md` | `templates/system-design.md` |
| `docs/adr/NNNN-*.md` | ADR | `adr-standard.md` | `templates/adr.md` |
| `docs/architecture/diagrams/*.puml` | Diagram | `diagram-standard.md` | — |
| `docs/operations/*.md` | Runbook | `documentation-governance.md` | `templates/runbook.md` |
| `docs/README.md` / `docs/specs/README.md` / `docs/adr/README.md` / `docs/adr/traceability.md` | Index | `documentation-governance.md` §4 | — |

> 索引类文档（README / traceability）**不是** Spec、不是 ADR，不得承载业务契约。

### 步骤 2~4 — 跑检查

按标准文件 §2 的 10 组（Type / Structure / Responsibility / Current Facts / Lifecycle / References / Architecture / Reliability / Consistency / Operations）逐项核对，**能用命令的不用目测**（§5 的 6 条 grep 为最小集）。

### 步骤 5 — 输出

使用标准文件 §3 的输出格式（含 Violations 表、Missing Sections、Drift、Broken References、Final Status）。

**结论**：`PASS`（无 P0/P1）/ `WARN`（无 P0，有 P1 或 P2）/ `FAIL`（存在任一 P0）。严重度定义见标准文件 §4.1。

## 硬约束

1. **不静默放行**：发现问题必须列出，不得「看起来没问题」而给 PASS。
2. **不改造被审查文档**（除非用户明确要求修复）：Review 的产出是**结论 + 证据（文件:行号）**。
3. **漂移只上报不改写**：发现 `代码 ≠ System Design`、`System Design ≠ Technical Solution`、`Active Spec ≠ 实现` 时，MUST 报告 `DOCUMENTATION_DRIFT`（证据 + 各方描述 + 待确认问题），交人类裁决。
4. **不重复造事实**：检查「服务是 9 个还是 10 个」这类事实时，读取 `technical-solution.md` 与 `constitution.md`，而不是引用本技能里的数字。
5. **索引必同步**：改 ADR 状态 → 同改 `docs/adr/README.md` + `docs/adr/traceability.md`；改 Feature 状态 → 同改 `docs/specs/README.md` + `roadmap.md`。检查时**双向核对**。
6. **历史不可回改**：`Accepted` ADR 正文、已归档审计记录、历史 Spec 的结论 MUST NOT 被「顺手订正」；只能新增说明。指向 System Design 的旧章号引用按 `system-design-standard.md` 附表换算，不回改正文。

## 常见 P0 速查（最容易犯）

- Current 文档把 **Proposed** 设计写成**现状**（如 ADR-0083 的 SLO 规则尚未落 L0）。
- 直接改写 `Accepted` ADR 的决策内容（应新建 ADR + 双向 Supersedes）。
- 引用不存在的 ADR / 不可达的锚点链接。
- Spec 落在 `docs/specs/<feature>/`（缺 stage）或顶层 `specs/`。
- 服务口径写成「10 个服务」（正确：**9 个业务服务** / **10 个运行进程**）。
- 把「重复支付自动退款（surplus）」当成缺口、或把 `ChannelCallbackSignatureFilter#verifySignature()` 描述为「已验签」。
