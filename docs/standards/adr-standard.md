# ADR 规范（ADR Standard）

> **Status**: Active
> **Authority**: L1。母规范见 [documentation-governance.md](documentation-governance.md)。
> **适用对象**：`docs/adr/NNNN-*.md`。

---

## 1. 定位

ADR = **一个重要且不可逆的架构决策**的记录。

回答：**为什么选择这个架构决策？**

- **一个 ADR 只记录一个重要决策。**
- 不写实现细节、不写 Feature 行为契约（分别属 System Design / Spec）。

---

## 2. 统一模板

```markdown
# ADR-NNNN: <Title>

Status: Accepted
Date: YYYY-MM-DD

## Context

（决策的背景与约束：什么问题、为什么现在必须决定）

## Decision

（决定了什么，正文陈述句）

## Alternatives

（考虑过哪些备选方案，各自为什么被排除）

## Consequences

（正面与负面后果、对下游的影响）

## Risks

（这个决策引入的风险与缓解）

## Related Documents

- Spec：...
- System Design：...
- Supersedes / Superseded by：...
```

### 章节要求

| 章节 | 必需 | 说明 |
|---|---|---|
| `Status` | MUST | 状态 + 日期 |
| `## Context` | MUST | 背景与约束 |
| `## Decision` | MUST | 决策内容 |
| `## Alternatives` | MUST | 备选方案与排除理由（历史 bundle 文件缺失此项的，触及该文件时补齐） |
| `## Consequences` | MUST | 后果与影响 |
| `## Risks` | MUST | 风险与缓解。**历史文件普遍缺失本章，属已知缺欠**：不得以此为理由继续省略新 ADR 的本章。 |
| `## Related Documents` | MUST | 相关 Spec / System Design / 取代关系 |

> **历史 bundle 说明**：本项目存在单文件承载多条决策的 bundle 文件（如 `0003-*.md` 含 ADR-0003~0007）。bundle 文件允许采用「状态总览 + 逐条决策」体，但**新增 ADR 一律单决策单文件**，并遵守 §3 命名规则。

---

## 3. 编号与命名

1. 编号**只增不改、不复用**，顺序递增 `0001`、`0002`…
2. 文件名：`NNNN-<kebab-slug>.md`，**文件名前缀 = 该文件内第一个 ADR 的编号**。
3. 编号水位登记在 `docs/adr/README.md` 的「下一可用编号」条目。
4. **新增 ADR 前 MUST 先 `git fetch` + 查远端水位**（并行会话会抢号）。
5. **禁止**产生新的重复 ADR ID。

### 3.1 ADR-0054 历史编号异常（保留现状）

`0054-core-payment-correctness.md` 与 `0054-order-payment-orchestration.md` **同时使用 ADR-0054**（历史成因见 `docs/adr/README.md`）。

- **保留现状**，不重新编号。
- 引用 ADR-0054 时 MUST **同时使用文件名 + 标题**消歧：
  - ✅ `ADR-0054（`0054-order-payment-orchestration.md`：支付编排职责归位）`
  - ❌ `见 ADR-0054`
- 未来禁止产生新的重复 ADR ID。

### 3.2 交叉引用写法（编号 MUST 与目标文件一致）

- 引用 MUST 写成 `[ADR-NNNN](NNNN-<kebab-slug>.md)`（或带 `../adr/` 前缀的等价相对路径），且 **NNNN 必须由目标文件承载**——即该文件内存在 `<a id="adr-NNNN"></a>`。
- ⚠️ **历史陷阱（2026-09-19 重命名遗留）**：文件名前缀是在 2026-09-19 才改为「文件内首个 ADR 编号」的（提交 `64704c0 docs: ADR 文件名改用「文件内首个 ADR 编号」作前缀`）。此前正文里按**旧文件序号**写的标签（如 `[ADR-0006](../adr/0016-refund-decisions.md)`）在重命名后与真实编号脱节——**修正方向是改标签，不是改文件**（→ `[ADR-0016](../adr/0016-refund-decisions.md)`）。
- 区间引用取第一个编号即可：`[ADR-0077~0079](0077-ledger-accounting-foundation-decisions.md)`。
- 指向索引类文件（`README.md` / `traceability.md`）时标签可写 ADR 编号，**豁免**本规则（索引本就不承载单个编号）。
- 自动校验：`python deployment/docs-lint.py` 的**检查 16**。

---

## 4. 状态机

```
Proposed（提案） → Accepted（已接受/生效） → Superseded（被取代）或 Deprecated（废弃）
```

| 状态 | 含义 |
|---|---|
| `Proposed` | 提案讨论中，尚未生效。**MUST NOT** 被当作现行约束引用。 |
| `Accepted` | 已批准，是当前权威约束。 |
| `Rejected` | 负责人明确否决；已有实现须回退（回退清单记于该 ADR 内）。 |
| `Not Implemented` | 方案认可但本期不落地。需区分「预留空实现」与「不做（代码已删）」。 |
| `Superseded` | 被新 ADR 取代；**新旧两端互相链接**。 |
| `Deprecated` | 决策不再适用但无替代者，保留作历史。 |

---

## 5. 变更规则

1. **Accepted ADR 不可变**：不直接编辑历史决策内容。
2. **决策变化 → 新建 ADR**：
   - 新 ADR 写 `Supersedes ADR-XXXX`；
   - 旧 ADR 状态改为 `Superseded`，并写 `Superseded by ADR-YYYY`；
   - **旧 ADR 与新 ADR MUST 互相引用**。
3. **部分取代**用 `Partially Supersedes ADR-XXXX`，并写明被取代的**具体条款**。
4. **状态更新 MUST 同步两个索引**：`docs/adr/README.md`（状态列）与 `docs/adr/traceability.md`（落点）。
5. 已生效决策 MUST 在 `traceability.md` 登记其 L0 落点。
6. **指向 System Design 的章号引用不回改**：ADR 内部若引用 `systems/<service>.md §X.Y`，该章号是**撰写时**的快照；System Design 重排章号后 MUST NOT 回改历史 ADR 正文，换算规则见 [system-design-standard.md §4](system-design-standard.md)。**但索引类文档（`README.md` / `traceability.md`）与指向 System Design 的 Markdown 锚点链接 MUST 保持可达**，否则按失效链接处理。

---

## 6. 何时写 ADR

- 架构 / 技术选型（框架、中间件、数据库边界、通信方式）
- 引入或移除服务 / 中间件 / 依赖
- 服务边界或数据所有权变化
- 破坏性迁移、安全策略、生产部署策略

涉及宪法 Governance「人类决策边界」的决策，MUST 先经负责人确认，再落 ADR。

---

## 7. Review 要点（详见 [documentation-review.md](documentation-review.md)）

- [ ] 7 段齐全（Status / Context / Decision / Alternatives / Consequences / Risks / Related Documents）
- [ ] 只有一个决策
- [ ] 编号唯一、文件名前缀 = 首个内部编号
- [ ] 状态与 `README.md` / `traceability.md` 一致
- [ ] Supersede 关系双向登记
- [ ] 引用 ADR-0054 时已消歧

---

## 8. 相关文档

- [documentation-governance.md](documentation-governance.md)
- [spec-standard.md](spec-standard.md)
- [../templates/adr.md](../templates/adr.md)
- [../adr/README.md](../adr/README.md)
- [../adr/traceability.md](../adr/traceability.md)
