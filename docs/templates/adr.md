# ADR-NNNN: <决策标题>

> **Status**: Proposed `<!-- Proposed | Accepted | Rejected | Not Implemented | Superseded | Deprecated -->`
> **Date**: YYYY-MM-DD
> **Standard**: [adr-standard.md](../standards/adr-standard.md)

> **使用方式**：复制到 `docs/adr/NNNN-<kebab-slug>.md`（**文件名前缀 MUST = 本文件内第一个 ADR 的编号**）→ 替换 `<...>` → 按每节「写什么 / 需要什么」填写 → 交付前跑 `/doc-review`。
> **建 ADR 前 MUST**：① `git fetch` + 查远端水位（并行会话会抢号）；② 查 `docs/adr/README.md` 的「下一可用编号」；③ 确认没有同范围 ADR 已被 Superseded 又重开。
> **一个 ADR 只记一个决策**：新建单决策单文件；`Accepted` 后正文**不可变**（要改就必须新建 ADR 并双向登记取代关系）。

---

## Context

**写什么**：背景与约束——**什么问题、为什么现在必须决定**。
**需要什么**：
- 事实性的现状描述（可核验）；不写主观评价。
- 约束清单：技术上必须遵守的边界、时间上的紧迫性来源。
- 如果这是对既有 ADR 的修正，MUST 在此点名被修正的 ADR 编号。
**不要写**：解决方案（放 Decision）；实现步骤。

## Decision

**写什么**：**决定了什么**，用陈述句、无歧义地写。
**需要什么**：
- 一句话能说清的决定 + 必要的分条展开（D1/D2/…）。
- MUST 写清决定的**适用范围**与**不适用**的边界，否则后人会过度外推。
- 若决定带来例外（"通常禁止 X，此处允许"），MUST 显式登记例外并说明理由。
**不要写**：可能、也许、待定；备选方案（放 Alternatives）。

## Alternatives

**写什么**：考虑过哪些备选方案，**各自为什么被排除**。
**需要什么**：每个备选一段：方案要点 → 优点 → 排除理由。至少给 **2 个**被排除的方案（只写一个通常意味着没真正比较）。
**不要写**：稻草人方案（明显不可行的凑数项）。

## Consequences

**写什么**：正面与负面后果、对下游的影响。
**需要什么**：
- 分「正面 / 负面 / 对下游（其他服务、运维、测试、文档）的影响」。
- MUST 承认代价：任何决策都有成本，写不出负面后果通常说明分析不完整。
**不要写**：风险的缓解措施（放 Risks）。

## Risks

**写什么**：本决策引入的风险与**缓解**。
**需要什么**：表格 `风险 | 触发条件 | 影响 | 缓解措施`；缓解措施 MUST 是**可执行**的（谁做什么），不是"注意一下"。
**不要写**：与决策无关的既有风险。

## Related Documents

**需要什么**：
- **Spec**：本决策由哪个 Feature 承载（`../specs/<stage>/<feature>/`）。
- **System Design**：影响哪个服务的文档（`../architecture/systems/<service>.md`）。
- **Supersedes / Superseded by**：取代关系 MUST **双向**登记（本 ADR 写 `Supersedes <编号>`，被取代者补 `Superseded by <本编号>`）。
- 其他相关 ADR：用 `[ADR-NNNN](NNNN-slug.md)` 形式，且**编号必须由目标文件承载**（见 [adr-standard.md](../standards/adr-standard.md) §3.2）。
**不要写**：正文摘抄。
