# Spec 规范（Spec Standard）

> **Status**: Active
> **Authority**: L1。母规范见 [documentation-governance.md](documentation-governance.md)。
> **适用对象**：`docs/specs/<stage>/<feature>/` 下的 Feature 文档。

---

## 1. Spec Kit 四件套

正常 Feature MUST 具备**四件套**，正式术语固定为：

| 文件 | 职责 | 回答 |
|---|---|---|
| `spec.md` | 行为契约 | 这个 Feature 必须实现什么行为？ |
| `plan.md` | 实现路径 | 怎么落地？ |
| `tasks.md` | 执行清单 | 具体改什么？ |
| `acceptance.md` | 判定完成 | 如何判定完成？ |

> **禁止**再使用「三件套」一词。历史文档中的「三件套」按上下文映射：指 `plan+tasks+acceptance` 时改为「Spec Kit 四件套的 plan/tasks/acceptance」；指 `spec+plan+tasks` 时改为「spec/plan/tasks」。

### 1.1 职责边界（禁止越权）

| 禁止 | 原因 |
|---|---|
| Plan 定义业务需求 | 业务需求是 Spec 的职责 |
| Tasks 定义架构决策 | 架构决策是 ADR 的职责 |
| Acceptance 重新定义业务规则 | 验收只能引用 Spec 的规则，不得新增/改写 |

---

## 2. `spec.md` 固定骨架（13 章）

| # | 章节 | 内容 |
|---|---|---|
| 0 | **Status**（头部元数据） | Lifecycle 状态（见 §4）、编号、日期、相关 ADR |
| 1 | **Problem** | 为什么做（现状缺口 / 动机） |
| 2 | **Goal** | 做什么（可判定的目标） |
| 3 | **Scope** | 范围内是什么 |
| 4 | **Non-goals** | 明确不做什么 |
| 5 | **Scenarios / User Stories** | 用户与场景（正常流 + 失败流） |
| 6 | **Functional Requirements** | `FR-NNN` 编号的需求清单 |
| 7 | **Business Rules** | 业务规则与不变量（`INV-NNN`） |
| 8 | **State / Lifecycle** | 涉及的状态与生命周期变化 |
| 9 | **Error / Edge Cases** | 错误分支、边界、异常 |
| 10 | **Idempotency** | 幂等键、重复请求、重复回调 |
| 11 | **Acceptance Criteria** | `SC-NNN` 验收标准（可验证） |
| 12 | **Dependencies** | 依赖的前置 Feature / ADR / 服务 |
| 13 | **Related Documents** | 链接到 ADR / System Design / 图 |

### 2.1 `plan.md`

回答「怎么落地」，MUST 覆盖：

1. **技术上下文**（涉及服务、端口、Schema）
2. **现状检查**（现有代码 / 契约的约束）
3. **数据模型与契约变更**
4. **影响范围**（哪些服务 / 表 / 端点）
5. **依赖顺序**
6. **一致性 / 幂等 / 失败恢复方案**
7. **风险与缓解**
8. **验证方式**（怎么证明它工作）
9. **回滚方案**

**禁止**：定义业务需求（引 Spec）、定义架构决策（引 ADR）。

### 2.2 `tasks.md`

回答「具体改什么」，每条 MUST 含：

- 任务 ID（`T##`）
- 涉及的**文件路径**
- 验收方式（测试 / 命令 / 断言）
- 对 `FR-nnn` / `INV-nnn` 的**追溯**（每条 `FR-*` 至少一个任务覆盖）

编号纪律：`tasks.md` / `acceptance.md` **MUST NOT** 自造 `spec.md` 中不存在的 ID；补充验收标「补充验收 · FR-nnn」。

**禁止**：定义业务规则、定义架构决策。

### 2.3 `acceptance.md`

回答「如何判定完成」，MUST 含：

- **INV 门禁**：每条不变量如何被验证
- **演示验收**：可复现的验收步骤
- **已知限制**：未覆盖 / 显式接受的缺口

**禁止**：重新定义业务规则（只能引用 Spec 的 FR/INV/SC）。

---

## 3. 路径

唯一合法路径：`docs/specs/<stage>/<feature>/`（见 [documentation-governance.md](documentation-governance.md) §4.3）。

---

## 4. 生命周期状态

`spec.md` 头部 MUST 使用统一 8 态（见 [documentation-governance.md](documentation-governance.md) §5.3）：

`Draft` / `In Review` / `Approved` / `In Development` / `Implemented` / `Deprecated` / `Superseded` / `Not Implemented`

- `spec.md` / `plan.md` / `tasks.md` / `acceptance.md` 四者对同一 Feature 的状态 MUST 一致。
- 正文已明确「实现完成 / tasks 全部完成 / 已合入」的 Feature，**MUST NOT** 继续保留 `Draft` / `Proposed` / 「待评审」。
- `docs/specs/README.md` 与 `roadmap.md` 的状态描述 MUST 与之一致。

---

## 5. design-only 例外

若一个 Feature 明确属于 **design-only / specification-only**（例如 UI / 设计规范类），可在 `spec.md` 头部声明：

```yaml
delivery_mode: design-only
```

此时**不强制**四件套齐全（可只有 `spec.md`，或 `spec.md + acceptance.md`），但：

1. 例外 MUST 在 `spec.md` 头部**显式声明**（禁止隐式豁免）；
2. 在 `docs/specs/README.md` 中 MUST 标注该例外；
3. 除显式声明 `design-only` 外，正常 Feature 默认必须具备四件套。

---

## 6. 写作要求

1. **可判定**：每条 FR / SC 必须能回答「怎么验证」。
2. **不越界**：不写其他 Feature 的设计、不写架构论证。
3. **编号唯一**：`FR-*` / `INV-*` / `SC-*` 在 Feature 内唯一；跨 Feature 引用时带 Feature 号。
4. **一次一件事**：一个 Feature 一个明确目标，不塞多个不相干能力。

---

## 7. Review 要点（详见 [documentation-review.md](documentation-review.md)）

- [ ] 四件套齐全（或已显式声明 `design-only`）
- [ ] `spec.md` 13 章齐全
- [ ] 状态在四件套之间一致，且与 README / roadmap 一致
- [ ] 路径含 stage
- [ ] 每条 FR 有任务覆盖
- [ ] 无越权内容（Plan 定需求 / Tasks 定决策 / Acceptance 定规则）
- [ ] 链接可达

---

## 8. 相关文档

- [documentation-governance.md](documentation-governance.md)
- [adr-standard.md](adr-standard.md)
- [../templates/spec.md](../templates/spec.md)
- [../specs/README.md](../specs/README.md)
