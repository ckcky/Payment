# 文档体系 / 架构图体系 / AI 规范 治理审查报告

> **Status**: archived（一次性审查报告，**不是**权威事实源，不产生任何已生效决策）
> **Date**: 2026-09-22
> **Scope**: `docs/**`、`AGENTS.md`、`CLAUDE.md`、`README.md`、`.claude/**`、`.specify/**`、`VERSION`、`CHANGELOG.md`
> **Method**: 只读审查。**未阅读任何业务代码**（`*-service/src/**` 未纳入），**未修改任何文件**。事实来源限定为文档自述。
> **对照基线**: `docs/architecture/technical-solution.md` 与 `docs/architecture/systems/*.md` 作为「Current System Facts」基准层。

---

## 0. 结论摘要（按严重度）

| # | 结论 | 级别 |
|---|---|---|
| C-1 | **Standards / Templates / Review / Diagram 四层治理完全不存在**。现有约束只有「宪法 + 三份 guides + ADR 状态机」，都停留在「原则」层，没有任何一层规定「文档骨架该长什么样、模板在哪、写完怎么查」。这是本次要建的东西，也是下面所有漂移得以反复出现的根因 | 🔴 P0 |
| C-2 | **三天前的同类治理已被漂移反超**。`docs/archive/audits/2026-09-19-docArch-audit/` 已完成一轮治理（生命周期标注 + 索引同步），但 030~035 的实现推进让 `traceability.md`、`docs/specs/README.md`、`technical-solution.md` 三处重新失准（详见 §4-E）。**说明缺的不是又一次审计，而是常态化约束层** | 🔴 P0 |
| C-3 | **Current 与 Proposal 在同一份 L0 文档内互相打脸**：`technical-solution.md:505` 称跨服务异步通道「未实现、ADR-0074 保持 Active Proposal」，而同文件 `:34`/`:198` 记为已实现；`:30`「10 个服务模块」与 §3.3/§3.6 的 9 个自相矛盾 | 🔴 P0 |
| C-4 | **宪法版本号与正文不一致**：`:267` 仍为 `Version 2.3.0 / Last Amended 2026-09-03`，正文 `:147` 已含「v2.4.0 增补（2026-09-20 / ADR-0074 落地）」；全部 8 处外部引用（AGENTS.md / docs/README / guides / stage-design 等）都写 2.3.0 | 🟠 P1 |
| C-5 | **AI 规则层引用了 12 处过时架构事实**（含已并入的 `refund-service`、已延后的 `gateway`、已在用的 Redis、已落地的沙箱链路、被明令「不做」的脱敏、「不引入跨服务异步事件」等），且命令文件引用的宪法章节号（§3/§8）在现行宪法中**不存在** | 🟠 P1 |
| C-6 | **skippit 技能与仓库实际落点冲突**：skills/scripts 硬编码顶层 `specs/`（该目录不存在），而全仓规范要求 `docs/specs/<stage>/<feature>/`；且「分支创建」依赖不存在的 `.specify/extensions.yml` → 分支环节实际落空 | 🟠 P1 |
| C-7 | **ADR 索引层已过期**：`traceability.md` 少登记 12 条 ADR、≥4 条状态漂移；`README.md` 顶部索引表保留旧文件序号、漏登记 9 个文件；ADR-0054 一号两文（已备案） | 🟠 P1 |
| C-8 | **文档骨架不统一**：9 篇系统设计中 `ledger-service.md` 结构性离群；`order-service.md` 缺 §7；`settlement-service.md`/`entitlement-service.md` 子节错位；9 篇**全部**缺「相关文档」章节；ADR 全库**无一**有独立 Risks 章节、16 个文件缺 Alternatives | 🟠 P1 |
| C-9 | **Spec 生命周期标注与实现不符**：11 种状态写法、11 个 feature「实现已落地但头部仍标 Draft/Proposed/待评审」、6 个 feature 内部四件套互相矛盾 | 🟠 P1 |
| C-10 | **路径与术语双轨**：spec 路径存在「含 stage / 不含 stage」两套口径（`docs/README.md:72` 与同文件 `:32` 自相矛盾）；相对链接指向 `docs/adr/` 有三种深度；「三件套」一词有两义 | 🟡 P2 |
| C-11 | **架构图无任何统一规则**：命名/渲染/手绘取舍只存在于 3 个 `.puml` 的文件头注释里；`.puml` 与 `.svg` 标题三处不一致；`.svg` 实为手绘而非渲染产物（`.puml` 注释自述），但文档呈现为「源→图」成对关系 | 🟡 P2 |

> 说明：C-9 中「002~007 等早期 Draft Spec 是否回写」在 2026-09-19 报告 §5 已被**显式登记为待人工裁决的治理待办**，本次不是新发现，而是**仍未处理**。

---

## 1. 现状盘点

### 1.1 文档资产（实际存在的 9 类）

| 类型 | 位置 | 数量 | 现状 |
|---|---|---|---|
| 总体技术方案 | `docs/architecture/technical-solution.md` | 1（707 行） | 越界承担 System Design 内容（见 §2-Q3） |
| 系统设计 | `docs/architecture/systems/*.md` | 9 | 8 篇同构，1 篇离群 |
| ADR | `docs/adr/*.md` + `README.md` + `traceability.md` | 42 文件 / 84 条决策 | 索引过期（见 §2-Q13） |
| 规范（guides） | `docs/guides/*.md` | 3 | 互相重叠且与命令冲突 |
| Feature 文档 | `docs/specs/<stage>/<feature>/` | 5 stage / 35 feature | 四件套缺 5 个、状态标注混乱 |
| 运维 | `docs/operations/` | 3（runbook + code-debt-backlog + split-proposal-template） | `docs/README.md` 只登记了 runbook |
| 演示设计系统 | `docs/design/DESIGN.md` | 1（241 行） | **`docs/README.md` 完全未收录** |
| 发版 | `docs/releases/` | 含 HTML deck | — |
| 归档 | `docs/archive/audits/`、`docs/archive/design/` | 7 + 1 | 已标注 Status |
| 架构图 | `docs/architecture/diagrams/` | 3 puml + 3 svg | 无规范（见 §2-Q14） |

### 1.2 已经存在的治理层（本次不应重复建设）

- 宪法（`.specify/memory/constitution.md`）：最高约束 + 人类决策边界 + 提交与合并节奏。
- `docs/guides/` 三份规范：AI 流程 / 技术流程 / 业务流程。
- ADR 状态机（`docs/adr/README.md` §状态机）：Proposed → Accepted → Superseded/Deprecated，另有 Rejected 与 Not Implemented。
- 上下文分层（L0/L1/L2/L3）与 Documentation Drift 上报义务（`AGENTS.md`、`docs/README.md`）。
- 流程约束：`spec-worktree.sh`、`.claude/skills/speckit-*`、`deployment/architecture-tests`（INV-3~7 结构门禁）。
- 审计归档惯例：`docs/archive/audits/<date>-<slug>/`。

### 1.3 缺失的治理层（本次任务的目标）

| 层 | 现状 | 后果 |
|---|---|---|
| **Standards** | 不存在 `docs/standards/` | 「文档该有什么骨架」无成文定义 → 9 篇系统设计 3 篇结构不同、ADR 章节各异 |
| **Templates** | 不存在 `docs/templates/`；`.specify/templates/` 是 spec-kit core 模板且路径口径冲突 | 新文档靠模仿既有文档 → 结构性偏差被逐份复制 |
| **Review** | 不存在统一 Review Checklist；仅有 3 个命令文件，且与规范冲突 | 「写完了就算完」，无一致性门禁 → 漂移只能靠事后审计发现 |
| **Diagram** | 无任何图规范 | 图与文档脱钩（手绘 svg、标题不一致、无更新义务） |

### 1.4 与 2026-09-19 上一轮治理的关系

| 上一轮承诺 | 实际结果 |
|---|---|
| Phase 2 把 Context Loading Policy 写进 `AGENTS.md` | ✅ 已做（`AGENTS.md` 现有该节） |
| Phase 3 Knowledge Promotion | ✅ 部分完成（6 个文件归档留痕） |
| Phase 4 生命周期元数据 | ⚠️ 只覆盖「Phase 3 确认候选集」（001/016/017/019/027/028 + 部分 ADR 索引）；**002~007 等明确留待下一批**，至今未做 |
| Phase 5 历史化分类 | ⚠️ 只标注了 `next-stage-design.md` 为历史；已交付的 001~026 仍留在 L2 位置与在办 feature 同构 |
| 建立 Standards / Templates / Review | ❌ **未纳入计划**（迁移计划的 Phase 2~6 中无此项） |

> 结论：上一轮治理解决了「**层级与生命周期**」，本轮要解决的是「**类型、骨架、模板与检查**」。两者互补，不冲突；本次 MUST NOT 推翻上一轮结论（尤其 ADR-0074 之后的状态已在前一轮之后又演进过，需按最新事实收口）。

---

## 2. 十五项检查逐条结论

### Q1 现有文档类型
见 §1.1。**判定：类型事实上齐全，但没有任何一处对「类型 → 职责 → 谁引用谁」做统一定义**（`docs/README.md` 是目录导航，不是职责矩阵）。

### Q2 文档之间职责是否重叠
**判定：❌ 存在体系级重叠。**

| 重叠点 | A 侧 | B 侧 |
|---|---|---|
| payment 两层结构表 | `technical-solution.md:152` | `systems/payment-service.md:59-75` |
| 表结构与基数口径 | `technical-solution.md:313-326` | `payment-service.md:57,163-166`（文档自述「与 technical-solution 同源，MUST 同步修改」） |
| 9 个状态机 | `technical-solution.md:330-340` | 各服务 §2.2（8 份） |
| 领域职责表 | `technical-solution.md:293-306` | 各服务 §1.1（9 份） |
| 复式记账映射 | `technical-solution.md:464-471` | `ledger-service.md:108-118` |
| 异步事件拓扑 | `technical-solution.md:349-358` | 5 份服务文档（payment/order/catalog/entitlement/fulfillment） |
| 超时/弹性阈值 | `technical-solution.md:564-576` | payment/reconciliation/settlement 各 §5.4 |
| Payment Limit 子域 | `technical-solution.md:530-536` | `payment-service.md:668-733` |
| 支付宝 notify 三段式 | `technical-solution.md:407` | `payment-service.md:359-372` |
| Feature 017 审计四核对 | `technical-solution.md:690-707` | `reconciliation-service.md:345-374` |

规范层重叠：分支/提交纪律（`AGENTS.md:115` MUST worktree vs `engineering-standards.md:60` 标题「推荐」）、spec 路径（`ai-standards.md:39` vs `engineering-standards.md:44`）、测试载体（`engineering-standards.md:33` 已落地 vs `technical-solution.md:220` 未落地）、金额表示（guides 一致，但命令文件冲突）。

### Q3 Technical Solution 是否越界承担 System Design 内容
**判定：❌ 越界（四类，均有证据）。** 该文档 `:15` 自述「字段级、状态机级、接口级细节见 systems/」，与实际内容冲突。

- **A 类｜表字段/数据模型 delta**：`:690-707`（Feature 017 整节：三张表 + 5 态状态机 + seed + AB/AD 前缀）；`:532`（limit 三表）；`:152`（单服务表级写入归属）。
- **B 类｜端点与 DTO 字段**：`:696-704`（逐条 `/internal/**` 端点 + 幂等键格式）；`:407`（支付宝 notify 三段式 + 响应体 `success`）；`:413`（`POST /payments/{id}/resolve` 与配置缺失时 503）；`:86-88`（直接给出类级挂点）。
- **C 类｜单服务内部实现**：`:383-399`（payment 内部调用链逐步描述）；`:530-536`（RESERVE/CONFIRM/RELEASE/EXPIRED 与 `used_minor` 权威）。
- **D 类｜详细流程图**：`:115-131`、`:367-397`、`:419-431`、`:435-442`（4 处 mermaid，含 20 步 sequenceDiagram）。

另有两处**文档内自相矛盾**：`:505` vs `:34`/`:198`（ADR-0074 状态）；`:30` vs §3.3/§3.6（服务数 10 vs 9）。

### Q4 System Design 是否结构统一
**判定：⚠️ 8/9 统一，1 篇离群 + 3 处结构瑕疵。**

- 统一骨架（8 篇）：`1 设计目标与约束 → 2 核心数据模型 → 3 接口详细定义 → 4 关键流程链路 → 5 存储与缓存 + 逻辑处理策略 → 6 部署拓扑与配置`。
- `ledger-service.md`：章节体系完全不同，**缺** API 契约章节、**无**部署章节、可观测仅一笔带过。
- `order-service.md`：章节号 `§6 → §8 → §9`，缺 §7。
- `settlement-service.md`：`### 1.4` 排在 `### 1.3` 之前。
- `entitlement-service.md`：`### 5.5` 落在 §6 之后。
- 9 篇**全部**缺「相关文档」章节；「测试/验收」仅 `ledger-service.md` §9 有。

### Q5 Spec / Plan / Tasks 职责是否清晰
**判定：⚠️ 职责分层清楚，但存在集合缺口与状态互斥。**

- 四件套不完整 **5 个**：`016`（缺 acceptance）、`026`（缺 acceptance）、`020`/`024`/`035`（仅 spec.md）。
- 同 feature 内状态互斥 **6 组**：`027`（plan 称未实施 vs spec/tasks/acceptance 称已完成）、`026`（spec ✅ vs plan Draft）、`018`、`019`、`021`、`022`（acceptance 称「待代码实施」而 spec 称已实施）。
- 旁证：`roadmap.md:85` 自述 020/024 属「UI 规范类无 tasks」——**该例外未被任何规范成文**，属事实豁免而非规则豁免。

### Q6 ADR 是否有统一格式
**判定：❌ 无。** 42 文件 / 84 条决策中：

- **无一**使用独立 `## Risks` 章节（风险并入 Consequences 或散落正文）。
- **16 个文件**缺 `Alternatives`（备选方案）。
- 13 个 bundle 文件（单文件承载 2~9 条决策）采用「状态总览 + 落地清单 + 裸加粗条目」体，与 `0002` 的模板体不一致。
- `0074` 缺 Consequences/影响段；`0058` 缺 Context 段。
- 一致的只有：`Status` 行、`Context/Decision/Consequences` 的**存在性**（在 12 个文件里）。

### Q7 文档是否有统一 Template
**判定：❌ 不存在。** `docs/templates/` 不存在。`.specify/templates/` 是 spec-kit 未定制的 core 模板（`.specify/memory/.constitution-template.json:3` `"source": "core"`），且其 `plan-template.md:5,50` 写的是 `specs/[###-feature-name]/`——与本仓 `docs/specs/<stage>/<feature>/` 口径冲突。

### Q8 文档是否有统一 Review 规则
**判定：❌ 不存在。** 无 `docs/standards/documentation-review.md`。现有替代品是 `.claude/commands/` 三个命令，但它们**本身与规范冲突**（见 Q9）。`engineering-standards.md:97` 有「文档漂移检查清单」，但只覆盖「代码改了文档要不要改」，不覆盖「文档本身是否合规」。

### Q9 AI Skill / Command 是否存在相互冲突
**判定：❌ 存在，且有两类致命冲突。**

**结构/流程类（最高影响）**
1. **落点冲突**：`speckit-specify/SKILL.md:84,88-93,107` 与 `.specify/scripts/bash/create-new-feature.sh:191,193,329-330`、`.specify/templates/plan-template.md:5,50` 一律使用**顶层 `specs/`**（该目录在本仓库不存在）；规范侧要求 `docs/specs/<stage>/<feature>/`。
2. **分支环节落空**：`speckit-specify/SKILL.md:76-78,281` 声明分支创建由 `before_specify` hook（git extension）处理，但 `.specify/extensions.yml` 不存在 → 技能「skip silently」；而 `ai-standards.md:17` 把 Branch 作为流水线的**显式阶段**。
3. **状态指针失效**：`.specify/feature.json` = `{"feature_directory": "docs/specs/001-core-business-model"}`，该目录**不存在**（实际在 `stage-01-core-mvp/` 下）→ 下游 plan/tasks/implement 会解析到不存在的 FEATURE_DIR。
4. **技能未收编**：`speckit-converge` / `-checklist` / `-taskstoissues` / `-constitution` 存在但未进入 `ai-standards.md` 的流水线定义。

**规则内容类（与规范/宪法冲突）**

| 冲突 | 命令/技能侧 | 规范侧 |
|---|---|---|
| 允许 `Money` 值对象 | `commands/review.md:13`、`payment-review.md:13` | `engineering-standards.md:20`、宪法 `:79`：**不启用** |
| 一致性用 `Saga + Outbox` | `review.md:14`、`payment-review.md:34`、`AGENTS.md:27` | 宪法 `:144`（Saga + RPC）、`README.md:76`（**无 Outbox**）、`technical-solution.md:198`（Redis 事务消息） |
| 要求敏感字段脱敏 | `payment-review.md:30`、`skills/observability/SKILL.md:15,32` | 宪法 `:193`（**本期不做**，ADR-0027） |
| Micrometer Tracing 当现状 | `observability/SKILL.md:16` | 宪法 `:181`（**未落地**，实为 TraceIdFilter+MDC） |
| Testcontainers 当默认集成测试载体 | `test.md:38` | `engineering-standards.md:34`（默认仍 H2，仅三类断言升 L2b） |
| 引用宪法章节号 §2.2/§3/§3.3/§4/§6/§7.3/§8/§9 | `review.md:12,14,23`、`payment-review.md:8,39`、`test.md:40`、`observability/SKILL.md:12,19`、`payment-domain/SKILL.md:14,20,31,35`、`architecture/SKILL.md:70,77` | 现行宪法用 Roman 编号（§II/§III/§IV/§V）+ 具名节（Governance / AI Development），**这些章节号不存在** |
| 引用已不存在模块 `project-structure` | `review.md:12` | `technical-solution.md:7`：已并入 §3.6 |

### Q10 Current / Proposed / Historical 是否混淆
**判定：❌ 混淆，三处系统性。**

1. **同一 L0 文档内**：`technical-solution.md:505`（Proposal）vs `:34`/`:198`（Current）。
2. **索引与实际**：`traceability.md:29` 记 ADR-0074「🟡 Proposed，待实现」，实际 `0074-*.md:5` 与 `README.md:36` 均为 Accepted→Implemented（2026-09-20）；`traceability.md:53` 把 0077~0082 一律记 Proposed，实际 0077~0082 已 Accepted，仅 0083 为 Proposed。
3. **同一 ADR 两处状态不一致**：ADR-0082 文件 `:6` 记 Accepted、`README.md:43` 记 Accepted，但 `README.md:138` 与 `traceability.md:53` 记 Proposed。
4. **L2 未区分完成度**：`docs/README.md:46-52` 与 `AGENTS.md:47-52` 把整个 `docs/specs/` 定义为 Active Feature Work；已交付的 001~026 与在办的 030~035 在索引里同样呈现为平铺链接（仅 stage-02/05 有零散正文注记）。

### Q11 已实现但文档仍标记为 Proposal
**判定：❌ 存在 11 处头部状态滞后（另有 6 组同 feature 内互斥见 Q5）。**

| Feature | 文档标注 | 与之矛盾的文档自述 |
|---|---|---|
| 002 | Draft | 其 acceptance 称「已在 001 主线内落地」；tasks 全部 `[X]` |
| 003 | In Development | 其 acceptance 称「通过（实现完成，测试全绿）」 |
| 004 | Draft（ADR-0008~0011 待决策） | `roadmap.md:11`：ADR-0008~0011 **Accepted** |
| 005 | Draft（ADR-0016~0018 待决策） | `roadmap.md:12`：0016 Rejected 已回退、0017~0018 Accepted |
| 007 | Draft（ADR-0022~0023 待决策） | `roadmap.md:12`：已按最简实现落地 |
| 010 | Proposed | 同句自述「代码按最简实现已落地」 |
| 030 | Draft（本轮只写 Spec，不写代码） | 其 tasks「142/142 已完成并合入 master」、acceptance「已实现（`e348090`）」；而 `specs/README.md:75` 又写「待开工」 |
| 031 | Draft v1.0（ADR-0077~0079 Proposed） | `specs/README.md:76`、`roadmap.md:79`、`CHANGELOG`：已实现，ADR Accepted |
| 032 / 033 / 034 | 设计完成，待评审 | `specs/README.md:77-79`、`roadmap.md:80-82`、`CHANGELOG`：已实现 |

另：`docs/specs/README.md:61` 的 stage-05 标题写「🟡 提案中，待负责人确认」，而**同一文件** `:83` 写「031/032/033/034 已实现」——同文件标题与正文互斥。

### Q12 文档路径规则是否一致
**判定：❌ 双轨。**

- spec 路径：`docs/specs/<stage>/<feature>/`（`docs/README.md:32,34,47,48`、`AGENTS.md:49-50`、`engineering-standards.md:44,45,135`）vs `docs/specs/<feature>/`（`docs/README.md:72`、`README.md:70`、`roadmap.md:166,581`、`ai-standards.md:39,73-75`、宪法 `:170`、`033/spec.md:508`）。**`docs/README.md` 内部即自相矛盾（`:32` vs `:72`）。**
- 相对链接深度：指向 `docs/adr/` 出现 `../../adr/`（55 处）、`../../../adr/`（12 处）、`../../../../docs/adr/` 等；feature 目录深度为 `docs/specs/<stage>/<feature>/` 时正确写法应为 `../../../adr/`。**抽查证据**：`017-accounting-audit/spec.md:7` 的 `](../../adr/0065-…)`、`015-multi-channel-payment/spec.md:135` 的 `](../../specs/028-channel-routing/spec.md)`（会解析为 `docs/specs/specs/…`）→ 疑为 stage 分层迁移后未回改（**推断，未逐条跑链接校验器**）。
- 「三件套」一词两义：`specs/README.md:4` = 四件套（spec+plan+tasks+acceptance）；`specs/README.md:76-79` = plan+tasks+acceptance；`docs/README.md:72` 与 `AGENTS.md:50` = spec+plan+tasks。
- 归档命名：规则为 `docs/archive/design/<date>-<stage-slug>/`，实际为 `2026-09-19-next-stage-011-014/`（slug 非 stage slug、文件名非 `stage-design.md`）。

### Q13 ADR 是否重复 ID
**判定：⚠️ 1 处已备案重复 + 1 处疑似同题分记。**

- **ADR-0054 一号两文**：`0054-core-payment-correctness.md` 与 `0054-order-payment-orchestration.md`，`README.md:147` 已备案（结论：暂保持现状，引用时以「文件名+标题」消歧）。
- **ADR-0056 与 ADR-0059 疑为同一决策**（启用 Nacos）：`0055-entry-and-infra-decisions.md:14-19` vs `0059-enable-nacos.md:7`（**推断**）。
- 编号完整性：0001~0083 **无断档**；42 个文件名全部符合 `NNNN-slug.md` 且前缀 = 首个内部编号（`README.md:144` 规则已全量应用）。
- 状态分布：Accepted 70 / Proposed 2（0047、0083）/ Rejected 1（0016）/ Superseded 3（0031、0038、0057）/ Not Implemented 8（0006、0027、0028、0034~0037、0052）。
- 取代关系登记不完整：`README.md:171` 要求「新旧两端互相链接」，但 ADR-0048 侧未见 `Supersedes ADR-0038`、ADR-0070 侧未见显式 `Supersedes ADR-0057`（**推断**）。

### Q14 架构图是否缺少统一规则
**判定：❌ 完全没有规则。**

- `docs/architecture/diagrams/`：`01-system-architecture`、`02-deployment-topology`、`03-funds-closed-loop`，各 `.puml` + `.svg` 成对；命名 `NN-slug` 未成文。
- **`.svg` 是手绘图，不是 `.puml` 的渲染产物**：`.puml` 文件头注释自述「PlantUML 默认输出同名 .svg，会直接覆盖文档展示用的**手绘图**！务必用 -o 指定输出目录」→ 「源与图成对」是呈现关系而非生成关系，**这份关键约定只存在于注释里**。
- 三处标题不一致（puml vs svg）：`系统架构分层` / `系统架构分层总览`；`单机部署拓扑（双模式 · 二选一）` / `单机部署拓扑`；`资金闭环` / `资金闭环流程与复式记账映射`。
- 风格不统一：`01`/`02` 为结构图（package/rectangle/node），`03` 为活动图（`start/stop/legend`）。
- 引用有效：全 `docs/` 仅 `technical-solution.md:162,164,460,462,623,625` 与 026 三处文本引用图文件，**全部指向存在的文件**。

### Q15 AI 规则是否引用了过时架构事实
**判定：❌ 12 处。**

| # | 规则文本 | 位置 | 冲突的 Current 事实 |
|---|---|---|---|
| 1 | 服务清单含 `refund-service` | `skills/architecture/SKILL.md:49` | `technical-solution.md:183`：Feature 015 已并入 payment-service，8085 退役 |
| 2 | 服务清单含 `gateway`（未标延后） | `architecture/SKILL.md:44,75` | `technical-solution.md:178,260`：MVP 不创建 |
| 3 | 「当前不引入 MQ 或跨服务异步事件」 | `architecture/SKILL.md:38` | `technical-solution.md:34,198`：Redis 事务消息已实现（ADR-0074） |
| 4 | 禁用清单含 Redis，无例外 | `architecture/SKILL.md:75` | 宪法 `:146` 例外一/二；`technical-solution.md:54` |
| 5 | 技术栈把 Nacos / Gateway / Micrometer Tracing 列为现有 | `architecture/SKILL.md:68` | `technical-solution.md:215,217,219`（Nacos/Gateway/Tracing 均标未启用或未落地）；⚠️ 同时 `adr/0059-enable-nacos.md` 显示 Nacos 已启用 → **L0 自身也需复核** |
| 6 | 卡号密钥脱敏 | `observability/SKILL.md:15,32` | 宪法 `:193`：本期不做（ADR-0027） |
| 7 | Micrometer Tracing 跨服务传播 | `observability/SKILL.md:16` | 宪法 `:181`：未落地 |
| 8 | 金额可用 `Money` VO | `commands/review.md:13`、`payment-review.md:13` | 宪法 `:79`、`engineering-standards.md:20`：不启用 |
| 9 | 一致性用 Saga + Outbox | `review.md:14`、`payment-review.md:34`、`AGENTS.md:27` | 宪法 `:144`、`README.md:76`、`technical-solution.md:198` |
| 10 | 宪法服务清单含 refund-service / gateway | 宪法 `:127` | `technical-solution.md:183,260` |
| 11 | 引用 `project-structure` | `review.md:12` | 已并入 `technical-solution.md` §3.6 |
| 12 | 集成测试默认 Testcontainers | `commands/test.md:38` | `technical-solution.md:220` 与 `engineering-standards.md:34` |

**附带的服务数口径混乱**：`technical-solution.md:30`「10 个服务模块」；`runbook.md:28`「9 个服务 + 1 演示组件 = 10 进程」；`deployment/README.md:122,140`「10 个服务」；`README.md:18`/`roadmap.md:11`「服务数 10→9」；`adr/0059:36`「10 个服务」。**哪一处是正确口径需人工确认**（本文不做判定，因为不能靠猜）。

---

## 3. 建议的治理动作（对齐本次目标，不与现有结论冲突）

> 原则：**只建约束层 + 修正与 Current 事实冲突的旧口径**；不重写业务文档、不重排 ADR 历史、不改领域模型。

### 3.1 新建 `docs/standards/`（7 份）

`documentation-governance.md`（类型/职责矩阵/目录与命名/Status/Current-Proposed-Historical/引用与生命周期）、`technical-solution-standard.md`、`system-design-standard.md`、`spec-standard.md`、`adr-standard.md`、`diagram-standard.md`、`documentation-review.md`。

### 3.2 新建 `docs/templates/`（5 份空模板）

`technical-solution.md`、`system-design.md`、`spec.md`、`adr.md`、`runbook.md`。**模板不得写入本项目的具体业务内容**，只留标题/章节/字段/填写说明。

### 3.3 最小修正清单（与 Current 事实对齐，不涉及业务结论）

| 类别 | 动作 | 涉及 |
|---|---|---|
| 版本号 | 宪法 `:267` 版本行与修订历史补 `2.4.0`（或按负责人裁定是否将 `:147` 表述回退） | `constitution.md`；8 处外部引用同步 |
| Current/Proposal | 修 `technical-solution.md:505`；统一 ADR-0074/0077~0082 的状态口径 | `technical-solution.md`、`traceability.md`、`adr/README.md` |
| 索引过期 | 补齐 `traceability.md`（12 条未登记）、`adr/README.md` 顶部索引表（9 个文件未登记、旧序号残留、0078/0079 无独立行） | `docs/adr/` |
| 服务数 | 统一 9/10 口径（**先由人工确认哪个正确**） | `technical-solution.md:30`、`deployment/README.md`、`010`/`roadmap` |
| 路径口径 | 统一为 `docs/specs/<stage>/<feature>/`，清掉 `docs/specs/<feature>/` 写法与「三件套」两义 | `docs/README.md:72`、`README.md:70`、`ai-standards.md:39,73-75`、`roadmap.md:166,581`、宪法 `:170` |
| 相对链接 | 统一 `docs/adr/` 相对深度（需跑一次链接校验确定真实失效集） | spec 文档 55+12 处 |
| 生命周期回写 | 按 Q11 清单回写 spec 头部状态（**包含被上一轮显式留待处理的 002~007**） | 11 个 feature |
| AI 规则同步 | 修 Q15 的 12 处 + 命令文件的宪法章节号 + `.claude/skills/architecture` 服务清单 | `AGENTS.md`、guides、`.claude/**`、宪法 |
| Spec Kit 落点 | `.specify/` 脚本与模板的 `specs/` → `docs/specs/`（或明确声明不适用并禁用相关脚本） | `.specify/scripts`、`.specify/templates`、`.claude/skills/speckit-*` |
| 目录登记 | `docs/README.md` 补 `docs/design/`、`operations/` 两份额外文件、新增 `docs/standards/` 与 `docs/templates/` | `docs/README.md` |

### 3.4 明确不做

- 不重写 ADR 历史正文、不重排 ADR 编号（ADR-0054 保持现状）。
- 不改任何业务结论（领域模型、状态机、幂等口径、资金规则）。
- 不批量历史化（moving 001~026 out of `docs/specs/`）——这属于上一轮登记的高风险分类，需单独裁决。
- 不引入新的检查工具链（CI 门禁改造属另一个决定）。

---

## 4. 需要人类决策的事项

| # | 事项 | 为什么需要人 |
|---|---|---|
| D-1 | **架构图规范的口径**：本次任务指令在「七、建立 Architecture Diagram Standard」的「项目架构图统一采用」处**被截断**，缺少工具/语法/落点/命名/生成流程/更新义务的具体要求 | 属规范制定，且现有事实（手绘 svg + puml 源）需要人确认保留还是改造 |
| D-2 | 服务数正确口径（9 还是 10；`deployment/README.md` 的「10 个服务」指什么） | 事实冲突，不能靠推断定案 |
| D-3 | 治理动作的范围与批次：只建 Standards/Templates（约束层），还是连 3.3 的修正清单一起做 | 涉及改动面与工作流 |
| D-4 | `docs/specs/` 是否引入「已交付/在办」区分（或归档已交付 feature） | 触及上一轮登记的高风险分类 |
| D-5 | 宪法 `:147` 的「v2.4.0 增补」是补 bump 版本号，还是回退该表述 | 宪法修订是最高约束变更 |
| D-6 | `.specify/` 脚本落点冲突的处置（改脚本 / 禁用 / 声明不适用） | 触及开发流程与工具链 |
| D-7 | 002~007 等早期 Draft Spec 的生命周期回写（上一轮已留待裁决） | 上一轮显式留待人工 |

---

## 5. 未覆盖 / 待验证

- **未运行任何工具**：相对链接失效集、`.specify` 脚本实际行为、`docs/**` 交叉引用完整性均基于文本比对的**推断**，未跑校验器。
- 未阅读 `CHANGELOG.md` 全文（仅用其判断实现状态）、未阅读 `docs/releases/**` 正文、未阅读 `.github/**`、`.trae/`、`.workbuddy*/`。
- 未阅读任何业务代码（本次任务明确要求）。
- 未纳入：`docs/specs/**` 各 feature 正文的 FR/INV/SC 语义正确性（仅检查编号冲突与状态标注）。
- 「服务数」冲突未做定案（见 D-2）。
