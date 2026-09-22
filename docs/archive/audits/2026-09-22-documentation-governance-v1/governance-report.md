# PaymentArch Documentation Governance v1 —— 执行与验收报告

> **执行日期**：2026-09-22
> **范围**：文档体系 / 架构图体系 / AI 开发规范（**不改业务代码、不改业务模型、不改状态机与账务规则、不重编号历史 ADR**）
> **上游输入**：[2026-09-22 治理审查报告](../2026-09-22-doc-governance-audit/audit-report.md)（Before 证据）、[2026-09-19 治理收口报告](../2026-09-19-docArch-audit/final-documentation-governance-report.md)
> **结论**：**PASS**（16/16 校验项通过，详见 §9）

---

## 0. 一页结论

| 项 | 治理前（Before） | 治理后（After） |
|---|---|---|
| 规范层 | 无 `docs/standards/`，规则散落在 `docs/README.md` 与各指南 | **7 份标准**（`docs/standards/`），含职责矩阵 / 固定骨架 / Review 清单 |
| 模板层 | 无空模板；新文档靠"抄上一篇" | **5 份空模板**（`docs/templates/`），零业务内容 |
| 文档类型 | 9 类资产，边界靠约定 | **8 类文档类型** + 责任矩阵（"谁不能重定义谁"） |
| Technical Solution | 12 章但含字段 / DTO / 端点 / 单服务内部细节 | 12 章骨架 + **MUST NOT 清单** + 「下沉到 System Design」规则 |
| System Design | 9 份各写各的章号（1.1/2.1/3.x…，payment 到 10 章） | **统一 15 章核心骨架**（域扩展自 §16 起），9 份全部迁移完成 |
| Spec 路径 | `docs/specs/<feature>/` 与 `<stage>/<feature>/` 并存 | **仅 `docs/specs/<stage>/<feature>/`**（Spec Kit 脚本 / 模板 / Skill 全部收口） |
| Spec 四件套 | "三件套"口径，acceptance 常缺 | **四件套** `spec/plan/tasks/acceptance`，`delivery_mode: design-only` 显式豁免（2 个 Feature：020 / 024 两个 UI 设计规范类） |
| Spec 状态 | 自由文本（设计完成 / 待评审 / 提案中 / 部分完成…） | **统一 8 态**；34 篇 spec.md 全部归位（**34 Implemented**，含合并远端后落地的 035） |
| 事实分层 | Current / Proposed / Historical 混写 | 三层模型 + 硬规则："Current 文档 MUST NOT 把 Proposed 写成现状" |
| 架构图 | 3 张图（命名按功能、无 C4 层级、puml 与 svg 关系不明） | **8 张 C4 分层图**（L1/L2/L3/Dynamic/Deployment），`.puml` 唯一事实源、`.svg` 生成物 |
| ADR 索引 | README 声明「44 文件」实际 42；0054 重号无消歧规则 | **42 文件**对齐 + **ADR-0054 强制消歧口径** + 状态机 + 双向 Supersedes |
| AI 体系 | Skill / Command **各自存一份架构事实**，含失效事实（refund-service、gateway、Saga+Outbox…） | 3 Skill 重写为**导航层**（权威源表 + 禁止复制事实）+ 3 Command 判据修正 + 新增 Review Skill/Command |
| 宪法版本 | 正文 2.4.0，外部仍引 2.3.0 | **统一 2.4.0**（保留版本历史） |
| 服务数口径 | "10 个服务" | **9 个核心业务服务 + 1 个 Demo 进程 = 10 个运行进程** |
| 自动化校验 | 无 | **16 项可复跑校验**（`deployment/docs-lint.py`）→ `TOTAL: 16/16 PASS` |
| ADR 交叉引用 | 正文按**旧文件序号**写标签，与真实编号脱节 | 7 处标签修正（如 `[ADR-0006](../adr/0016-…)` → `[ADR-0016](../adr/0016-…)`）+ **检查 16** 常态化 |

---

## 1. Before —— 治理前的真实状态

依据 [审查报告](../2026-09-22-doc-governance-audit/audit-report.md) §1~§2 的 15 项逐条结论，问题归为 8 类：

1. **缺规范层**：文档类型、职责边界、骨架、Review 规则只存在于 `docs/README.md` 的几行说明和口头约定里，新文档无"必须长什么样"的可执行定义。
2. **缺模板层**：新增文档一律靠复制邻近旧文档，旧文档的越界内容被一并复制（Technical Solution 越界写 System Design 内容即由此放大）。
3. **职责重叠**：Technical Solution 承担了字段、DTO、端点定义、单服务内部细节、单个 Feature 的完整设计与状态机——这些属于 System Design 与 Spec。
4. **System Design 结构不统一**：9 份文档章号体系互不相同（`1.1`/`2.1.1`/`3.11`/`5.4`/`6.3`…），payment-service 写到第 10 章、ledger-service 只有 5 章 + 附录，跨文档引用无法机械化。
5. **路径双轨**：`docs/specs/001-core-business-model/`（无 stage）与 `docs/specs/<stage>/<feature>/` 并存；`.specify/feature.json` 指向一个**不存在**的目录 `docs/specs/001-core-business-model`。
6. **生命周期漂移**：Spec 状态用自由文本（"设计完成 / 待评审 / 提案中 / 部分完成 / 已完成"），且多篇"正文已写实现完成、tasks 全绿、已合入 master"的 Spec 头顶仍是 `Draft` / `Proposed`；ADR-0074 正文已 `Accepted → Implemented`，而外部描述仍作"生效中的提案"。
7. **事实分层混乱**：Current（现存有效事实）/ Proposed（未生效）/ Historical（已替代）三层混写，`docs/systems/payment-service.md` 把不存在的 `channel_mode` 列写成已实现。
8. **AI 事实自持**：`.claude/skills/*` 与 `.claude/commands/*` 各自"记"了一套架构事实，与 L0 冲突处至少 12 条（refund-service 已并入 payment-service / gateway 不在 MVP / Redis 事务消息已实现 / Money 值对象未启用 / Saga+Outbox 非现状 / 引用不存在的宪法 §3、§8 / 架构 Skill 服务清单过时…）。

---

## 2. After —— 新增的治理层（可执行，不是文档摆设）

治理链路闭环为：

```
Constitution → Standards → Templates → 实际文档 → Review → AI Agent → 实现 → 测试/CI → 回写 Current Facts
     ↑                                                                                      │
     └────────────────────────────── Drift 上报（不静默改写）────────────────────────────────┘
```

### 2.1 新增规范（7 份，`docs/standards/`）

| 文件 | 解决的问题 | 关键可执行内容 |
|---|---|---|
| [documentation-governance.md](../../../standards/documentation-governance.md) | 类型混乱、职责重叠、路径双轨、生命周期漂移 | 8 类文档定义、§3 责任矩阵（3.1~3.8 逐类 + 3.9 越界禁令）、§4 目录与命名（含 §4.3 Spec 路径硬规则）、§5 事实三层 + 8 态、§6 引用与权威（ADR-0054 消歧 / `DOCUMENTATION_DRIFT`）、§7 生命周期规则、§8 Review 闭环 |
| [technical-solution-standard.md](../../../standards/technical-solution-standard.md) | Technical Solution 越界 | 12 章固定骨架；§3 MUST NOT 清单（字段 / DTO / 端点 / 单服务内部 / 单 Feature 完整设计与状态机 / 重复 System Design）；保留「摘要 + 链接」 |
| [system-design-standard.md](../../../standards/system-design-standard.md) | 9 份结构不统一 | 15 章核心骨架；域扩展排在 §16 之后且不得打乱核心章号；**章号迁移映射表（本次已完成）**；旧章号引用不回改规则 |
| [spec-standard.md](../../../standards/spec-standard.md) | Spec 职责与四件套 | spec.md 13 章；plan/tasks/acceptance 各自职责；`delivery_mode: design-only` 豁免；禁止项 |
| [adr-standard.md](../../../standards/adr-standard.md) | ADR 格式与状态 | 固定模板（Status/Date/Context/Decision/Alternatives/Consequences/Risks/Related Documents）；状态机；变更时双向 `Supersedes`；指向 System Design 的**章号**不回改、但**索引类链接 MUST 可达** |
| [diagram-standard.md](../../../standards/diagram-standard.md) | 图无层级、无配色规则、源码与产物关系不明 | **采纳 C4 思维但不引新工具链（继续 PlantUML，优先 C4-PlantUML 风格）**；每个元素 MUST 写明 Element Type/Name/Responsibility/Technology/关系方向/关系标签；L1~L5 图层级定义；`puml = SOURCE OF TRUTH`、`svg = GENERATED ARTIFACT`；配色与命名规则；每图必须有 Legend |
| [documentation-review.md](../../../standards/documentation-review.md) | 无统一 Review | §1 执行流程、§2 **10 组检查项**、§3 输出格式、§4 **PASS/WARN/FAIL + P0/P1/P2**、§5 六条可复制的 grep 命令 |

### 2.2 新增模板（5 份空模板，`docs/templates/`）

[technical-solution.md](../../../templates/technical-solution.md) · [system-design.md](../../../templates/system-design.md) · [spec.md](../../../templates/spec.md) · [adr.md](../../../templates/adr.md) · [runbook.md](../../../templates/runbook.md)

- 与对应 Standard 骨架**逐章对齐**，复制即可开工，**不含任何 Payment 业务内容**。
- 内部占位链接按"目标位置"书写，因此**豁免**链接可达性检查（在 [documentation-review.md](../../../standards/documentation-review.md) §4 与本仓库校验脚本中均已登记为例外）。
- **标题一律中文**（英文章名以括号附注，与 9 份 `systems/*.md` 现状一致），并**逐节给出「写什么 / 需要什么 / 不要写」**——每个模板顶部另有「使用方式 + 硬边界」块。
- **需要什么**里显式点名必备产物，例如：System Design **§4 领域模型 MUST 含 Mermaid `erDiagram`**（标基数与唯一键、只画本服务表）、§5 状态图、§7 时序图；Technical Solution 的每类 C4 视图必须链接到 `diagrams/*.svg` 并写元素六要素；Spec 的 Tasks/Acceptance 只能引用本文件已存在的 `FR-*`/`INV-*`/`SC-*`（禁止自造 ID）。
- 各模板的**相对链接深度按其目标落点**写死并逐条核对（如 `system-design.md` → `../../standards/`），模板内注明"复制到别处 MUST 重新核对深度"。

### 2.3 新增 AI 治理文件（2 份）

- [.claude/skills/documentation-review/SKILL.md](../../../../.claude/skills/documentation-review/SKILL.md)：执行层技能，权威源指向 [documentation-review.md](../../../standards/documentation-review.md)；类型 → Standard → Template 映射表；5 步流程；6 条硬约束（不得静默放行 / 默认只读 / drift 只上报 / 不重造事实 / 索引必须同步 / 历史不得回改）。
- [.claude/commands/doc-review.md](../../../../.claude/commands/doc-review.md)：`/doc-review <file> [--fix]` —— 识别类型 → 载入 Standard → 载入 Template → 跑 10 组检查 → 输出 PASS/WARN/FAIL。默认只读；`--fix` 按 P0→P1 顺序修复，**排除已 Accepted 的 ADR 正文与归档审计**。

---

## 3. 架构图治理

### 3.1 治理前

`docs/architecture/diagrams/` 只有 3 张图，按"功能"命名、无层级概念：

- `01-system-architecture`（把系统上下文与容器混在一张图）
- `02-deployment-topology`
- `03-funds-closed-loop`

且 `.puml` 与 `.svg` 之间是**隐式的"看起来 puml 是源码、svg 是另一张图"**关系——同名的两个文件在视觉上并非同一张图。

### 3.2 治理后（目录精确为 8 组）

| 文件 | C4 层级 | 说明 |
|---|---|---|
| `01-system-context` | **L1 System Context** | 人 / 外部系统 / PaymentArch 边界 |
| `02-container-overview` | **L2 Container** | 9 个核心业务服务 + 1 个 Demo 进程 + 数据库 |
| `03-payment-components` | **L3 Component** | 仅对真正需要下钻的容器（payment-service）展开 |
| `04-payment-flow` | **Dynamic** | 正常支付主链路 |
| `05-payment-unknown-recovery` | **Dynamic** | UNKNOWN 状态恢复（主动查询 / 重试 / 超时扫描） |
| `06-refund-flow` | **Dynamic** | 订单驱动退款闭环 |
| `07-ledger-reconciliation-flow` | **Dynamic / 域运行流** | 资金闭环 + 记账 + 对账 |
| `08-deployment` | **Deployment** | 部署拓扑（宿主 / 容器双模式） |

- 3 张旧图按 C4 语义**重新落位并重制**：`01-system-architecture` → System Context / Container 两图；`02-deployment-topology` → `08-deployment`；`03-funds-closed-loop` → `07-ledger-reconciliation-flow`。
- 新增 Payment Component / Payment Flow / UNKNOWN Recovery / Refund Flow / Ledger-Reconciliation Flow 五张。
- **L3 只给 payment-service**：明确禁止把每个 Controller/Service/Repository/Mapper 都当 Component 画（[diagram-standard.md](../../../standards/diagram-standard.md)）。
- `.puml` 为**唯一事实源**，`.svg` 为生成物；渲染命令 `-tsvg -charset UTF-8`；渲染产物只写入目标目录，不覆盖展示图。
- 视觉规范统一：白底；Person 浅灰 / 内部 Container 蓝灰 / 外部 System 灰 / Database 标准形状；主流程蓝 / 成功绿 / UNKNOWN 橙 / 失败红；边框 1px、圆角 8–12px；每条关系必须有方向 + 建议"动词 + 技术协议"；每图必备 Legend。
- 3 张旧图文件已从仓库移除（`01-system-architecture.{puml,svg}`、`02-deployment-topology.{puml,svg}`、`03-funds-closed-loop.{puml,svg}`）。
- 引用侧同步：[technical-solution.md](../../../architecture/technical-solution.md) §3.2（L1+L2 双图）、§4.3.5（`07`）、§6（`08`）；026 容器化 Feature 的 spec/plan/tasks 的旧图名引用已更新并**注明"当时文件名为…"**的重命名溯源。

---

## 4. AI 治理

### 4.1 原则变更（核心）

> **AI 规则不再"存"架构事实，只"读"权威文档。**

所有 Skill/Command 顶部新增"**权威源表**"，写明"要了解 X，去读 Y"，并显式声明"**本技能不定义架构事实**"。这一条同时被写进 [documentation-governance.md](../../../standards/documentation-governance.md) 与 [AGENTS.md](../../../../AGENTS.md)。

### 4.2 具体改动

| 文件 | 改动 |
|---|---|
| `.claude/skills/architecture/SKILL.md` | **重写为导航层**：权威源表（technical-solution / 02-container-overview.puml / systems/ / 宪法 §III·§IV·§V / adr README+traceability / docs/standards）；服务口径（9 业务 + 1 Demo = 10 进程；gateway 不在 MVP；refund-service 已由 ADR-0064 退役）；payment-service 两层结构（ADR-0072）；一致性 = Saga 思路 + 同步 RPC + 幂等 + 调用方补偿台账，**禁用 2PC/XA**；Redis Streams 事务消息通道已实现（ADR-0074），**不引入 MQ/Kafka/ES**；分层 `api → application → domain ← infra`；技术栈行注明 Spring Cloud Gateway 不在 MVP、Micrometer Tracing 未落地 |
| `.claude/skills/observability/SKILL.md` | **重写为导航层**：四支柱 + 现状表（结构化日志+traceId ✅ / 资金审计 ✅ / Micrometer+Prom+Grafana+Loki ✅ / 业务告警 ✅ / **Micrometer Tracing ❌ 未落地** / **敏感数据脱敏 ❌ 本期不做（ADR-0027）** / Testcontainers ⚠️ 仅真库语义用例）；ADR-0083 在合并远端 035 落地后已为 🟢 Accepted（本报告初稿写作时尚为 🟡 Proposed，见 §5.8） |
| `.claude/skills/payment-domain/SKILL.md` | **重写为导航层**：铁律取自宪法 §II（含"未知状态不猜成败"）；边界表修正（#5 退款是 order 驱动的跨域编排 ADR-0067 而非服务；#6 payment 只通知 order-service ADR-0054）；一致性问答改引 §V；禁止项补"**重复支付自动退款是既有能力，不是缺口**" |
| `.claude/commands/review.md` | 判据全部改为**真实存在的章号**（§IV / §Engineering Standards 2 / §II / §V / §AI Development 3·4 / §Governance › 人类决策边界），并加"不要凭记忆引用条款号"；Money 值对象**当前未启用**；四件套 + `docs/specs/<stage>/<feature>/` 口径；drift → `DOCUMENTATION_DRIFT` |
| `.claude/commands/payment-review.md` | 6 组检查按现状重写：记账幂等口径（ADR-0082/C-19）、下单入口 Redis 四态（ADR-0039/0040）、终态吸收、反向路径恒取 `payment_attempts.channel_code`、**JSON 回调验签为预留空实现（ADR-0025/0052）不得描述为"已验签"**（Alipay notify 才是真 RSA2）、脱敏"不做"的正确审查角度、失败恢复归属（ADR-0082）；显式提示 surplus 自动退款不是缺陷 |
| `.claude/commands/test.md` | 测试载体分层：单元/领域单测 · **集成测试默认 H2** · **Testcontainers 仅真库语义用例**（ADR-0081）；Docker 缺失时 skip + 汇总；CI `real-db` job；门禁 `./mvnw -B verify`；输出必须标注载体 |
| `.claude/skills/speckit-specify/SKILL.md` | 删除"存在 hook 会自动建分支"的**不实声明**（`.specify/extensions.yml` 不存在）；唯一权威执行 = `bash .specify/scripts/bash/create-new-feature.sh --stage <stage> --short-name "..." "..."`；写明 stage 解析优先级（`--stage` > `SPECS_STAGE` > `repo-config.json default_stage`）与跨 stage 全局顺序编号；新增 stage 属人类决策边界 |

### 4.3 AI 规则与 Current Facts 的 12 条冲突——逐条修正

| # | 原表述（错） | 修正为（对） | 依据 |
|---|---|---|---|
| 1 | 存在独立 `refund-service` | 已并入 payment-service（退款域） | ADR-0064 / ADR-0067 |
| 2 | 系统含 Spring Cloud Gateway | **不在 MVP** | 宪法 §III / L0 |
| 3 | 跨服务一致性 = Saga + Outbox | **Redis Streams 事务消息通道（已实现）**；不引 MQ | ADR-0074 / spec 029 |
| 4 | Money 值对象已启用 | **当前未启用** | L0 |
| 5 | 引用宪法 §3 / §8 | 不存在的章号 → 改引真实章号 | 宪法 v2.4.0 |
| 6 | 架构 Skill 服务清单过时（含 gateway/refund-service） | 9 业务服务 + 1 Demo 进程 | L0 + 用户裁定口径 |
| 7 | tracing 已落地 | Micrometer Tracing **未落地** | L0 |
| 8 | 敏感字段脱敏已做 | **本期不做**（透传桩） | ADR-0027 |
| 9 | Testcontainers 是所有集成测试默认载体 | **仅真库语义用例**；默认 H2 | ADR-0081 |
| 10 | 回调验签已生效 | JSON 通道为**预留空实现**；Alipay notify 真 RSA2 | ADR-0025 / ADR-0052 |
| 11 | 重复支付自动退款是缺口 | **是既有能力**（`surplusRefund(... "DUPLICATE_PAYMENT")`） | spec 016 / ADR-0054 |
| 12 | Spec 三件套 / `docs/specs/<feature>/` | 四件套 / `docs/specs/<stage>/<feature>/` | 本次治理裁定 |

### 4.4 Spec Kit 修正

| 项 | Before | After |
|---|---|---|
| `feature.json` | 指向不存在的 `docs/specs/001-core-business-model` | 指向真实活跃 Feature `docs/specs/stage-05-channel-and-finance-deepening/035-observability-slo`（新增 `.specify/repo-config.json`） |
| `create-new-feature.sh` | 硬编码 `SPECS_DIR="$REPO_ROOT/specs"`，只扫单层 | **仓库级覆盖**（不破坏第三方核心结构）：新增 `--stage`；`specs_dir`/`stage_layout`/`default_stage` 从 `repo-config.json` 解析；stage 无法解析时**报错并列出可用阶段**；`get_highest_from_specs` / `spec_prefix_exists` 改为**递归（跨 stage 全局编号）** |
| `plan-template.md` / `tasks-template.md` / `spec-template.md` | 指向 `specs/[###-feature-name]/`，三件套 | 指向 `docs/specs/<stage>/[###-feature-name]/`；四件套；8 态词表；并注明上游 `research.md`/`data-model.md`/`quickstart.md`/`contracts/` **非本仓库必需产物** |
| 冒烟验证 | — | `--dry-run --json` → `{"BRANCH_NAME":"036-governance-smoke","SPEC_FILE":".../docs/specs/stage-05-.../036-governance-smoke/spec.md","FEATURE_NUM":"036","DRY_RUN":true}`（带/不带 `--stage` 均通过） |

---

## 5. 已修复的漂移（Drift Fixed）

### 5.1 状态漂移

- **ADR-0074** → 正文与索引统一为 `🟢 Accepted → Implemented`（2026-09-20 随 spec 029 批次 A~G 落地，`--no-ff` 合入 master `5e2c00d`），不再是"生效中的提案"。
- **ADR-0077~0082** 状态统一：0077~0079 / 0080 / 0081 / 0082 均 `Accepted`（含落地说明）。**ADR-0083** 在本次治理进行期间由并行的 035 会话推进：2026-09-21 负责人批量裁决 🟡 Proposed → 🟢 **Accepted**，2026-09-22 随 spec 035 落地（本报告初稿写于裁决前，故其中"0083 保持 Proposed"的表述已被 §5.8 的合并校正覆盖）。
- **Spec Status 重写**（优先清单 002/003/004/005/007/010/030/031/032/033/034 + 各 Feature 内 Plan/Spec/Tasks/Acceptance 互相矛盾）：正文已写"实现完成 / tasks 全绿 / 已合入 master"的，一律从 Draft/Proposed 改为 **Implemented** 并附依据。合并远端 035 落地提交后，最终 **34 篇 spec.md = 34 Implemented**（035 由初稿的 `In Review` 校正为 `Implemented`，其 `delivery_mode: design-only` 声明同步移除——plan/tasks/acceptance 三件已齐，不再满足豁免条件）。
- **Feature 内一致性**：acceptance / plan / tasks / specs README / roadmap 的措辞与 `spec.md` 状态行对齐。
- `docs/adr/README.md` + [docs/adr/traceability.md](../../../adr/traceability.md) 与 ADR 实际状态对齐；历史信息**零删除**。

### 5.2 索引与编号漂移

- **ADR 索引数量**：README 原称「ADR 完整文件清单（44 文件，权威）」，实际 ADR 文件 **42** 份（44 = 42 + README + traceability）。已修正为 **42 文件** 并明确"不含本 README 与 traceability.md"。
- **ADR-0054 重号**：**保留原样、不重编号**。README 顶部新增强制消歧口径——引用 ADR-0054 时 **MUST 同时使用文件名 + 标题**（`0054-core-payment-correctness.md` 与 `0054-order-payment-orchestration.md`），并**禁止**今后产生新的重复 ADR ID；完整清单中以 `0054（A）/（B）` 双行登记。

### 5.3 路径与链接漂移（8 类真实死链已修）

| 类 | 症状 | 修复 |
|---|---|---|
| 1 | ADR → Spec 链接用旧的无 stage 路径 | `../specs/<feature>/…` → `../specs/<stage>/<feature>/…`（6 个 ADR / 15 条链接） |
| 2 | `docs/adr/0074-redis-transactional-message.md` 引用 3 个**不存在**的 ADR 文件名（`0029-column-and-fulfillment-granularity.md` / `0019-payment-and-order-responsibility.md` / `0026-cross-system-business-no.md`） | 按**标签编号**纠正到真实承载文件：→ `0066-schema-normalization-and-item-granular-fulfillment.md`、`0054-order-payment-orchestration.md`、`0063-cross-service-reference-by-business-no.md`（⚠️ 初次修复曾按"话题相似"误映射到 0067 / 0062，已纠正——见 §5.7） |
| 3 | `docs/standards/technical-solution-standard.md` 链接 `systems/payment-service.md`（少一层） | → `../architecture/systems/payment-service.md` |
| 4 | `docs/architecture/systems/*.md` → ADR 相对深度少一层 | `../adr/` → `../../adr/`（9 份） |
| 5 | `.specify/memory/constitution.md` → `docs/` 深度错 | → `../../docs/…`（6 处） |
| 6 | `.claude/skills/*/SKILL.md` → 仓库根深度少一层 | `../../docs/` → `../../../docs/`（5 个文件，含 `.specify/`） |
| 7 | `docs/templates/*.md` 占位链接深度 | → `../standards/…` / `../architecture/diagrams/…`（模板目录整体登记为链接检查豁免） |
| 8 | 历史 Spec 在 stage 分组后相对深度错 + 跨 stage 引用错（015 → 028） | 批量重算（27 + 3 文件）→ `../../stage-04-new-directions/028-channel-routing/spec.md` |

### 5.4 事实准确性漂移

- `docs/architecture/systems/reconciliation-service.md`：把**不存在**的类 `CsvChannelStatementLoader.load(...)` 改为真实的 `` `StatementParser.parse(channelCode, period, content)` `` + [CsvStatementParser.java](../../../../reconciliation-service/src/main/java/com/payment/reconciliation/infra/CsvStatementParser.java)，fixture 名为 `{period}.csv`；`INTERNAL_ERROR` 行同步更正；并以「术语变更留痕」保留旧表述。
- `docs/architecture/systems/entitlement-service.md` §12 启动顺序：上游"fulfillment-service / **refund-service**" → "fulfillment-service / **payment-service（退款域）**"。
- `docs/architecture/technical-solution.md`：`systems/payment-service.md §2.3 → §4.2`、`reconciliation-service.md §7 → §16`。

#### 5.4.1 「跨服务异步」口径回写（人类裁定 2026-09-22）

**裁定**：现行口径为「**不引入 MQ 中间件，改用既有 Redis（`redis:7`）Streams 模拟 MQ，以减少需要新增与长期运维的组件**」。据此修正以下把「不引入 MQ / 无跨服务异步事件」当作**现状**的表述：

| 文件 | Before | After |
|---|---|---|
| [docs/guides/engineering-standards.md](../../../guides/engineering-standards.md)（L1，AI 每次开发必读） | 「跨服务用 Saga + 同步 RPC + 幂等重试；**当前不引入 MQ 或跨服务异步事件**；禁止 2PC/XA」 | 「…；**不引入 MQ 中间件**——跨服务**异步解耦**改用**既有 Redis（`redis:7`）的 Streams 模拟 MQ** 的事务消息能力，**目的是减少需要新增与长期运维的组件**（复用已存在 Redis 依赖，禁 Kafka/RocketMQ 等新运维实体）；禁止 2PC/XA」+ 指向宪法 §IV / §V.3 增补与 ADR-0074 |
| [README.md](../../../../README.md)「当前边界」 | 「…落库留痕（**无 MQ、无 Outbox**，见 ADR-0031…）；集成测试用 H2（**未引入 Testcontainers**）」 | 改为「异步解耦用 Redis Streams 模拟 MQ（**不引入 MQ 中间件**，见 ADR-0074；无 Outbox）」；Testcontainers 口径同步更正为「**真库子层已落地**（ADR-0081）」 |
| [.claude/skills/architecture/SKILL.md](../../../../.claude/skills/architecture/SKILL.md) | 「已引入 Redis Streams 事务消息通道…未引入 MQ / Kafka / ES」 | 补上**决策理由**（复用既有 Redis、减少组件新增与运维）与「模拟 MQ」口径 |

**未回改（按治理规则保留为历史快照）**：宪法 §IV L141 + §V.3 增补（**已正确**，无需改）、[ADR-0001](../../../adr/0001-adopt-spring-cloud-microservices.md) 决策原文（**改为加法式口径注明**，注明其为 2026-08 快照并指向 ADR-0074）、`001-core-business-model/tasks.md`「本阶段不引入…」（历史阶段范围声明）、ADR-0074 G8 行（对**旧宪法表述**的证据引用，属记录）。

### 5.5 历史文档的**加法式**处理（不做批量历史化）

按用户明确约束（"不要批量历史化 001~026、不要大改业务文档"），对合法存在历史事实的文档**只做加法**，不重写正文：

- **25 份**历史文档插入 `**历史文档提示（2026-09-22 文档治理）**` 标记（退场服务名 `refund-service` 等）；
- **5 份**插入 `**口径注明…**` 标记（"10 个服务"→"10 个运行进程"口径）；
- 该标记模式已在 [documentation-governance.md](../../../standards/documentation-governance.md) 中**预登记为治理惯例**，校验脚本据此豁免；
- `001`~`026` 全部**保留在 `docs/specs/` 原位**，仅在生命周期字段标注 Implemented / Historical。

### 5.6 System Design 章号迁移（9 份全部完成，内容零丢失）

统一到 15 章核心骨架（域扩展自 §16 起），迁移映射已写入 [system-design-standard.md](../../../standards/system-design-standard.md) §4：

| 服务 | 章数（治理后） | 域扩展 |
|---|---|---|
| catalog / entitlement / fulfillment / merchant / settlement | 15 | — |
| ledger | 16 | §16 Ledger 域扩展（Posting / Accounting Event / 借贷 / 记账规则） |
| order | 17 | §16 订单域、§17 履约驱动 |
| reconciliation | 18 | §16~§18 对账域 |
| payment | 19 | §16~§19 支付域（两层结构 / 路由 / 渠道契约 / 回调） |

- 迁移以**确定性脚本**完成（内容保留），并用新旧非标题行多重集差异比对验证；`ledger-service.md` 因版式独特改由人工迁移。
- 迁移前备份：`deployment/output/backup-systems-docs/`。
- **历史 ADR 正文内的旧章号不回改**（撰写时快照），但**索引类文档**（`README.md` / `traceability.md`）与指向 System Design 的 **Markdown 锚点链接 MUST 可达**——已重算：traceability 6 行、technical-solution 2 处、030 spec/acceptance。

### 5.7 深度复核新发现（2026-09-22 二次复核）

收敛后做了一次「编号语义」层面的复核（不只是"链接能不能打开"），新发现并修复 2 类问题：

**（1）ADR 标签编号与目标文件脱节（7 处）**

`docs/adr/*` 文件名是在提交 `64704c0 docs: ADR 文件名改用「文件内首个 ADR 编号」作前缀` 中才改为「前缀 = 文件内首个 ADR 编号」的；该提交**只改文件名、未改 `<a id>` 锚点**，因此正文里按**旧文件序号**写的标签全部与真实编号脱节。经核实（例：旧 `0006-refund-decisions.md` → 新 `0016-refund-decisions.md`，标签仍写 `ADR-0006`），**修正方向是改标签而非改文件**：

| 文件 | Before（标签编号错） | After |
|---|---|---|
| [docs/adr/0074-redis-transactional-message.md](../../../adr/0074-redis-transactional-message.md) | `[ADR-0054](0067-order-driven-refund-…)`、`[ADR-0063](0062-business-no-snowflake.md)` | `[ADR-0054](0054-order-payment-orchestration.md)`、`[ADR-0063](0063-cross-service-reference-by-business-no.md)` |
| [docs/architecture/technical-solution.md](../../../architecture/technical-solution.md) | `ADR-0006` / `ADR-0009` / `ADR-0011`（指向 0016 / 0024 / 0034 文件）、`ADR-0023`（指向 0063）、`ADR-0004`（指向 0008） | `ADR-0016` / `ADR-0024` / `ADR-0034`、`ADR-0063`、`ADR-0008`（标签与承载文件一致） |

> 判定依据：这些行的**目标文件在话题上是正确的**（`technical-solution.md` 第 11 行的上下文是"鉴权/验签、出站令牌、风控、脱敏、部分退款"，恰好落在 0024 / 0034 / 0016 三份文件里），所以错的是标签，不是链接。
> 同时把该规则**写进规范**：[adr-standard.md](../../../standards/adr-standard.md) §3.2「交叉引用写法」+ 校验脚本**检查 16**（现状 165 条 ADR 交叉引用全部一致）。

**（2）图表规范与 L0 现状自相矛盾**

[diagram-standard.md](../../../standards/diagram-standard.md) 原写「**不引入** Mermaid / draw.io / 其他图形框架」，但 `technical-solution.md` 与 **7/9** 篇 `systems/*.md` 本来就在用 Mermaid（`sequenceDiagram` ×10、`graph LR` ×1、`flowchart LR` ×1），且规范自身适用范围只写 `diagrams/*.puml` —— 属于规范与现状打架。

- **修法（不推翻现状、不新增工具）**：规范新增 **§1.0「两类图的分工」**，明确 —— 跨服务架构图属 `diagrams/*.puml`（PlantUML、C4 分层、唯一事实源）；**文档内行内示意**（ER / 时序 / 状态 / 流程）是**既有事实**，继续用 Mermaid，仅受视觉规范约束、**不替代**架构图、MUST NOT 放进 `diagrams/`；同时禁止引入第三种图形语法。
- 与之配套，[system-design-standard.md](../../../standards/system-design-standard.md) §5 新增第 7 条「图随章节走」：**§4 MUST 含 ER 图**、§5 状态图、§7 时序图，且行内图必须与正文表格一致。

### 5.8 与并行会话的合并校正（2026-09-22 晚）

治理提交固化后发现 `origin/master` 已前进 **13 个提交**（`feature/035-observability-slo` 当日合入，PR #16），其中 **8 个文件与本次治理改到同一文件**。按仓库纪律（**禁 rebase**）以 **merge** 方式合并，4 个文件冲突逐个人工裁决：

- `docs/adr/README.md`、`docs/adr/traceability.md`、`docs/architecture/roadmap.md`、`docs/specs/README.md`

**裁决原则**：**事实层取更新的远端版本；规则层保留本次治理的增补**。

| 冲突点 | 裁决 | 依据 |
|---|---|---|
| ADR-0082 / 0083 索引行 | 取远端（0083 已 🟢 Accepted、告警扩容至 24 条） | 远端为更晚的事实 |
| 「下一可用编号」水位行 | 取远端（七条 ADR 全部裁决收口） | 同上 |
| 「编号冲突备案」条目 | 取远端 + **回补**本次的「2026-09-22 治理裁定」句 | 两者互不冲突，都需保留 |
| traceability「仅 0083 仍 Proposed」段 | 取远端（design-only / 未裁决状态解除） | 远端为更晚的事实 |
| roadmap stage-05 状态行 | 取远端（五 Feature 全落地、27 项裁决收口） | 同上 |
| specs/README **030 行** | **保留本次的 `Implemented`** 并补证据，**不取远端** | ⚠️ 远端该行仍写「Spec v1.1 + Plan 三件已就绪，**待开工**」、状态说明仍称「030 及其余 Feature 仍为提案」——**与事实不符**：`origin/master` 上已有 **5 个 030 分支 PR（#5~#9）** 合入（沙箱凭证 / INV-7 门禁 / 本地隧道 / notify 验签修正 / Payment-Channel 边界复审 FIX-1~4），`payment-service/src/main/java/com/payment/payment/application/channel/` 下统一渠道契约代码（`PaymentChannel` / `ChannelRegistry` / `ChannelRouter` / `AlipayGateway` / `ChannelResult`）齐全 |
| specs/README 状态说明段 | **8 态体例取本次** + **事实取远端**（030~035 全部 Implemented） | 规则用本次、事实用远端 |

**合并后连带校正 3 处被事实推翻的治理结论**（均属事实层，非规则层）：

1. `035-observability-slo/spec.md`：`In Review` + `delivery_mode: design-only` → **`Implemented`**，并移除豁免声明（plan/tasks/acceptance 三件已齐，不再满足 design-only 条件）；
2. `.claude/skills/observability/SKILL.md`：ADR-0083 从「🟡 Proposed，尚未进入 L0」→「🟢 Accepted → Implemented」，并指向 `docs/operations/runbook.md §5` 的指标目录唯一登记处；
3. 本报告 §0 / §5.1 / §9 / §10 相关表述同步校正（不掩盖初稿的旧结论）。

**合并后复跑**：16 组检查仍为 `16/16 PASS` —— 说明两边改动在**规则层无冲突**，仅在事实层需要按"就新不就旧"裁决。

---

## 6. 变更文件清单（按区域）

工作区统计：**146 项已跟踪文件变更（140 修改 + 6 删除）/ 34 个新增文件**，累计 `+2057 / -1238` 行（不含新增文件）。

| 区域 | 文件数 | 说明 |
|---|---|---|
| `docs/specs/` | 100 | Spec 状态归位 / 四件套声明 / 路径与深度修正 / 历史标记 |
| `docs/architecture/` | 17 | technical-solution（5 处引用修正 + 双图） + systems/ 9 份重构 + diagrams 删旧 6（3 组 puml+svg） |
| `docs/adr/` | 10 | 状态统一 / 死链与**标签编号**修正 / README 索引 42 文件 + 0054 消歧 / traceability 章号换算 |
| `docs/guides/` `docs/operations/` | 3 | 口径校正（含 §5.4.1 的 Redis 模拟 MQ 口径） |
| `docs/`（根） | 1 | `README.md`（挂载治理层与三处口径） |
| `.claude/skills/` | 4 | 3 个重写为导航层 + speckit-specify 修正 |
| `.claude/commands/` | 3 | review / payment-review / test 判据重写 |
| `.specify/` | 5 | constitution 版本+路径 / create-new-feature.sh 覆盖 / 3 份模板 |
| 根 | 2 | `AGENTS.md`、`README.md` |
| `deployment/` | 1 | `README.md` |
| **新增文件（34）** | 34 | `docs/standards/`(7) · `docs/templates/`(5) · `docs/architecture/diagrams/`(16) · `.claude/skills/documentation-review/SKILL.md` · `.claude/commands/doc-review.md` · `.specify/repo-config.json` · `deployment/docs-lint.py` · `docs/archive/audits/2026-09-22-documentation-governance-v1/governance-report.md` · `docs/archive/audits/2026-09-22-doc-governance-audit/audit-report.md` |

**业务代码改动：0。** 全部变更中，非 Markdown 的只有 3 项：`.specify/scripts/bash/create-new-feature.sh`（Spec Kit 仓库级覆盖，用户明确许可）、`.specify/repo-config.json`（新配置）、`deployment/docs-lint.py`（文档校验脚本，纯标准库、无新依赖）。**无任何 `.java` / `.sql` / `.xml` / `.yml` / 前端文件被修改**。

---

## 7. 未做的事（按约束显式遵守）

| 约束 | 遵守情况 |
|---|---|
| 不读业务代码反推架构 | ✅ 全部事实取自 docs / 宪法 / ADR / Spec / AI 文档 |
| 不改业务模型 / 状态机 / 幂等规则 / 账务规则 | ✅ 零改动（无 Java 变更） |
| 不重编号历史 ADR | ✅ 0001~0083 编号全部原样，ADR-0054 重号**保留** |
| 不大改业务文档 | ✅ 历史文档只加标记，正文不重写 |
| 不批量历史化 001~026 | ✅ 全部留在原位，仅标注生命周期 |
| 不建新复杂工具链 | ✅ 仅 Markdown + PlantUML + 既有脚本 / Spec Kit / Skill / Review。**唯一的显式新增**是 `deployment/docs-lint.py`（16 组只读检查、纯 Python 标准库、零依赖、与既有 `deployment/schema-lint.sh` 同类）——没有它就无法产出"可复跑的 PASS 证据"，也无法满足"统一的新文档写入后检查"。**该新增已在此显式声明，便于人类否决。** |
| 不改软件核心结构 | ✅ Spec Kit 以仓库级覆盖（`--stage` + `repo-config.json`）实现，不破坏第三方骨架 |
| 不模拟"通过" | ✅ 校验脚本可复跑；结论以真实输出为准 |

---

## 8. 校验方法与工具

- **校验脚本**：`deployment/docs-lint.py`（**16 组检查**，可复跑：`python deployment/docs-lint.py`，退出码 0 = 全绿）。脚本**随仓库跟踪**（不再放 gitignored 的 `deployment/output/`），任何克隆都能直接复现结论。
- **复用既有门禁**：`./mvnw -B verify`（架构测试 INV-3~INV-7 位于 `deployment/architecture-tests`）；文档改动不影响编译产物，回归结果见 §9。
- **链接检查口径**：解析 Markdown 内联链接 + 图片链接 + 显式 `<a id="…">` 锚点；slug 计算保留下划线（GitHub 语义）；**按渲染语义剔除围栏代码块与行内代码**（规范类文档会故意收录"错误写法"反例，否则会把反例判成死链）。
- **豁免登记**（显式、非静默）：`docs/templates/`（占位链接按目标落点书写）、`docs/archive/`（历史审计）、`docs/standards/`（内含 grep 模式与反例路径）。

---

## 9. 校验结果

`python deployment/docs-lint.py` 最终输出：

```
==============================================================================
[PASS] 1. Markdown 内部链接可达（模板目录豁免）
[PASS] 2. Spec 路径口径统一
[PASS] 3. ADR 索引完整（README 覆盖全部 ADR 文件）                (共 42 个 ADR 文件)
[PASS] 4. ADR 编号重复（仅允许 0054 历史例外）                     (已知例外：ADR-0054（2 处）)
[PASS] 5. Spec 状态使用统一 8 态                             (共 34 篇 spec.md)
[PASS] 6. 无自由文本状态
[PASS] 7. Spec 四件套齐备（design-only 例外已声明）               (共 34 个 Feature，其中 design-only 2 个)
[PASS] 8. 宪法版本号统一（无未加限定的 v2.3.0 引用）
[PASS] 9. 无过时服务名退款服务（未加历史标注的）
[PASS] 10. 服务数口径（禁止「10 个服务」）
[PASS] 11. 架构图引用可达 / 无残留旧图                            (8 puml / 8 svg)
[PASS] 12. System Design 15 章骨架
[PASS] 13. 模板齐备且无业务内容
[PASS] 14. AI 体系引用治理层且链接可达                            (检查 25 个 AI 规则文件)
[PASS] 15. 索引类文档章号引用已换算（历史 ADR 除外）
[PASS] 16. ADR 交叉引用编号与目标文件一致
==============================================================================
TOTAL: 16/16 PASS
```

（检查 16 = 本次二次复核新增的「ADR 交叉引用编号与目标文件一致」，覆盖 165 条 ADR 交叉引用。）

### 9.1 用户要求的 15 项验证 —— 逐项对应

| # | 要求项 | 对应检查 | 结果 |
|---|---|---|---|
| 1 | Markdown 链接 | 检查 1 | PASS |
| 2 | Spec 链接 | 检查 1 / 2 | PASS |
| 3 | ADR 链接 | 检查 1 / 3 / **16** | PASS |
| 4 | Template 一致性 | 检查 13 | PASS |
| 5 | 必需章节 | 检查 12 / 13 | PASS |
| 6 | ADR 状态一致 | 检查 3 / 4 | PASS |
| 7 | Spec 状态一致 | 检查 5 / 6 | PASS |
| 8 | Current/Proposed/Historical 一致 | 检查 6 / 8 / 9 / 10 | PASS |
| 9 | `docs/specs/` 路径一致 | 检查 2 | PASS |
| 10 | 图表引用 | 检查 11 | PASS |
| 11 | AI Skill 引用 | 检查 14 | PASS |
| 12 | ADR 索引完整 | 检查 3 | PASS |
| 13 | ADR 编号重复 | 检查 4 / 16 | PASS |
| 14 | 过时服务名 | 检查 9 | PASS |
| 15 | 过时架构事实 | 检查 8 / 10 / 12 / 14 / 15 | PASS |

### 9.2 构建门禁

`./mvnw -B verify` 执行结果：**文档改动不影响编译与测试产物**——除 `architecture-tests` 模块外**各模块全部构建、测试通过**。

`architecture-tests` 有 **1 项失败**，且**与本次治理无关、为预存在环境问题**：

```
[ERROR] com.payment.arch.AccountingVocabularyBoundaryTest
        .accountCodeAndDirectionLiteralsMustBeConfinedToLedgerAndContractEnums
Expecting empty but was: [
  ".workbuddy\p3-removed-refund-service\src\main\java\com\payment\refund\infra\client\FeignLedgerPostingGateway.java:66 → \"DEBIT\"",
  ".workbuddy\p3-removed-refund-service\src\main\java\com\payment\refund\infra\client\FeignLedgerPostingGateway.java:67 → \"CREDIT\""]
Tests run: 14, Failures: 1, Errors: 0
```

**根因**（已定位到行）：该 ArchUnit 测试用 `Files.walk(repoRoot)` 扫全仓 `.java`，判定"模块源码"的规则在 [AccountingVocabularyBoundaryTest.java `isModuleSource()`](../../../../deployment/architecture-tests/src/test/java/com/payment/arch/AccountingVocabularyBoundaryTest.java) 第 133–148 行——只排除 `deployment/` 与含 `target` 的路径，**未排除点目录**。于是：

- `.workbuddy/p3-removed-refund-service/src/main/java/...` 的 `src` 落在第 2 层 ⇒ 被当成合法模块源码 ⇒ 误报。

**与本次改动无关的证据**：

1. 违规路径位于 `.workbuddy/`，该目录被 **`.gitignore:44`（`.workbuddy/`）忽略**，**不受版本控制**；
2. 该备份副本 mtime = **2026-08-29 17:24**、目录 mtime = **2026-09-05**，均早于本次会话（2026-09-22）；
3. 本次会话 `git status --porcelain` 过滤 `.java` **命中 0 个**——**零业务代码改动**，不可能引入该文件。

**处置（人类裁定 2026-09-22：不处置）**：负责人指示「`.workbuddy/` 这个文件夹下的文件不用关心」。因此**不改动** `isModuleSource()`——修它属架构测试代码改动，且 [AGENTS.md](../../../../AGENTS.md) 明确禁止"改测试迎合实现"。`.workbuddy/` 是项目数据目录、**禁止删除**。本项归为**已知环境噪声**，不再列为待办（见 §10 第 8 项）。

> 说明：该失败会让 `./mvnw -B verify` 在本工作区**变红**，但这**不是**本次治理引入的回归，也**不是**"模拟通过"可以掩盖的——本节按真实输出如实记录。

---

## 10. 剩余已知缺口（Remaining Known Gaps）

均为**本次治理范围外**或**需人类决策**的项，已显式登记，不做静默处理：

1. **`docs/architecture/systems/payment-service.md` 的 L0 内容实为"拟议"而非现状**（如把不存在的 `channel_mode` 列写成已实现）。按 [AGENTS.md](../../../../AGENTS.md)「L0 文档漂移只上报、不自行改写」，本次**仅上报**，未改写；需负责人确认后单独修。
2. ~~**ADR-0083 仍为 `Proposed`**~~ —— **已消解**：2026-09-21 负责人裁决 🟢 Accepted，2026-09-22 随 spec 035 落地（并行会话 PR #16），本次合并后 **035 = `Implemented`、34 篇 spec.md 全为 Implemented**（见 §5.8）。初稿曾据当时状态把 035 记为 `In Review`，属**当时正确、现已过期**，已校正。
3. **`docs/specs/README.md` / `roadmap.md` 的阶段归属**：Feature 编号与阶段命名属宪法 §Governance 人类决策边界，本次未改动，仅做一致性对齐。
4. **历史 ADR 正文内的 System Design 旧章号未回改**（按 [adr-standard.md](../../../standards/adr-standard.md) §5 第 6 条，属设计选择）：索引类链接已保证可达，但 ADR 正文内的章号是撰写时快照。
5. **校验脚本的链接检查是"可达性 + 编号一致性"而非完整语义正确性**：检查 1 覆盖死链与路径错、**检查 16** 覆盖 `[ADR-NNNN]` 标签与目标承载文件是否一致，但仍无法判断"链接到的文档是否还讲这件事"（例：030 行的"待开工"描述与 5 个已合并 PR 之间的矛盾，脚本查不出——见 §5.8）。
6. **AI Skill 的"不定义事实"约束无机器强制**：目前靠条款 + Review 流程约束，未做 CI 门禁（引入需人类决策）。
7. **`.claude/skills/speckit-*` 其余技能**（analyze / checklist / clarify / constitution / converge / implement / plan / tasks / taskstoissues）本次仅逐项核对与 `docs/specs/<stage>/<feature>/` 口径对齐，未做重写。
8. ~~`architecture-tests` 的 ArchUnit 误报~~ —— **人类裁定：不处置**（2026-09-22，负责人指示「`.workbuddy/` 这个文件夹下的文件不用关心」）。成因记录保留在 §9.2 供后续追溯：`AccountingVocabularyBoundaryTest.isModuleSource()` 未排除点目录，导致 gitignored 的 `.workbuddy/p3-removed-refund-service/`（47 个 `.java` 的 2026-09-05 备份）被计入扫描。**结论：该测试在工作区含 `.workbuddy/` 备份时会红，属环境噪声，非代码缺陷**；`.workbuddy/` 为项目数据目录、**禁止删除**。
9. ~~`engineering-standards.md:27` 与 ADR-0074 冲突~~ —— **本轮已修复，见 §5.4**。

---

## 11. 验收结论

> ### **PASS**

- **每一项文档类型都有清晰职责**：8 类定义 + §3.1~3.8 责任矩阵 + §3.9 越界禁令，写入 [documentation-governance.md](../../../standards/documentation-governance.md)。
- **新文档不需要参考旧文档才能写**：5 份空模板与 Standard 骨架逐章对齐，复制即可开工。
- **统一的后编写检查**：[documentation-review.md](../../../standards/documentation-review.md) 10 组检查 + `/doc-review <file>` 命令。
- **架构图有统一层次与视觉规范**：C4 L1~L5 + 配色/命名/关系方向规则 + 每图 Legend，8 张图全部落位。
- **决策可追溯**：ADR 固定模板 + 状态机 + 双向 Supersedes + `traceability.md` 章号已换算。
- **Feature 生命周期统一**：8 态词表；34 篇 spec.md 全部归位；自由文本状态已消除。
- **AI 不再保存自己的架构事实**：3 Skill 重写为导航层 + 权威源表；12 条冲突逐条修正。
- **唯一有效事实基础**：Current / Proposed / Historical 三层 + L0/L1/L2/L3 上下文策略 + `DOCUMENTATION_DRIFT` 上报机制。
- **新增文档 / 图表 / ADR / Feature 均进入统一治理流程**：Spec Kit 脚本与模板已收口到 `docs/specs/<stage>/<feature>/`，图表目录与命名已规范，ADR 模板与索引同步规则已就位。
- **自动化证据**：`TOTAL: 16/16 PASS`（`python deployment/docs-lint.py`，退出码 0）。

### 11.1 PASS 的边界（诚实声明）

- 本 PASS 的**判定对象是文档治理目标**（**16 组**自动化校验 + 用户给出的 15 项验收清单）。
- 二次复核中**自查出并修复了 2 类自己引入/未察觉的问题**（§5.7）：ADR 标签编号误映射（含上一轮我自己的 2 处错误映射）与图表规范自相矛盾。说明本 PASS 是**多轮复核后的结果**，不是一次成型的结论。
- **`./mvnw -B verify` 在本工作区为红**，原因是 §9.2 记录的**预存在** ArchUnit 误报（扫描到 gitignored 的 `.workbuddy/` 备份副本），**与本次改动无因果关系**。该项经负责人 2026-09-22 裁定为「**不处置**」（环境噪声，见 §10 第 8 项），已从待办清单移除。
- 本次**未修改任何业务代码**，也**未以任何方式掩盖该失败**。

---

**执行者注**：本次为**纯治理改动**，无业务代码变更。所有"看起来像新事实"的内容都来自已有 L0/L1/L2/L3 文档或用户本次的固定裁定；所有历史信息以加法方式保留可追溯性，未做删除或静默改写。
