# 文档审查报告（Documentation Review Report）

- **日期**：2026-09-07
- **范围**：PaymentArch 项目文档体系（docs/ 及 deployment/ 相关脚本）
- **对象**：结构完整性、与开发进度的匹配度、组织合理性
- **方法**：不修改代码；对照 `docs/README.md` 定义的文档体系，逐项核查 `docs/adr/`、`docs/specs/`、`docs/architecture/`、`deployment/` 实际内容与编号一致性。
- **Status**：文档体系**整体成熟、层级清晰、且具备罕见的「自我披露偏差」透明度**；发现 1 项**关键运维/文档风险**（reset.sh 迁移漂移）与若干**编号一致性瑕疵**，均不影响产品正确性，但建议在收口时修正。
- **修复状态（2026-09-08）**：① 原报「reset.sh 迁移漂移」经复核已消解——基础 schema 文件（`01-order-schema.sql`/`03-payment-schema.sql`）已含 018/019 全部列（并行会话已合并回填），`reset.sh` 重放 2 位前缀即产出正确结构；② 编号瑕疵中的 **015 重号**已修复——孤儿 spec `025-snowflake-business-no` 重命名为 `025-snowflake-business-no`（合法 015 为 `015-multi-channel-payment`，被 ADR-0024 引用）；`spec 008` 缺号仍作已知缺口保留（无对应内容，疑似历史跳过）。修复已提交。

---

## 1. 文档体系结构完整性

依据 `docs/README.md`，体系为 `Constitution → ADR → 总体技术方案 → 系统设计 → Roadmap → Feature Spec`，职责与维护时机均有明确表格式定义（单一事实源、Spec Kit 工作流、文档 Review 规则齐全）。

| 维度 | 评估 | 说明 |
|---|---|---|
| 顶层体系文件 | ✅ 完整 | `docs/README.md` 目录导航、职责表、层级图、工作流、规则齐备 |
| ADR 索引 | ✅ 优秀 | `docs/adr/README.md` 含 0001–0069 全量交叉引用、状态机、编号规则、冲突备案 |
| 系统设计文档 | ✅ 完整 | `docs/architecture/systems/<service>-service.md`（含已退役 `refund-service.md` 正确标注）|
| Roadmap | ✅ 完整且诚实 | `docs/architecture/roadmap.md` 显式记录已收口偏离与遗留风险 |
| 工程规范 / 开发入口 | ✅ 存在 | `docs/guides/engineering-standards.md`、`development-guide.md`、`ai-workflow.md` |
| Feature Spec | ⚠️ 编号瑕疵 | 22 个 spec 目录，存在**缺号（008）**与**重号（两个 015）**，缺顶层索引 |

**结论**：结构骨架完整、职责边界清晰，主要短板在 Spec 编号连续性与顶层索引。

---

## 2. 关键发现（Findings）

### F1（严重 · 运维+文档）— `reset.sh` 迁移漂移：根因级隐患
`deployment/demo/reset.sh` 第 24 行：
```bash
for f in "$SCHEMA_DIR"/[0-9][0-9]-*.sql; do   # 仅 2 位前缀
```
- 该 glob **只重放 2 位前缀**的 `01-*`~`10-*` 全量 schema，刻意排除 3 位增量迁移 `015/016/018/019`。脚本注释称 3 位文件是「存量环境增量迁移」「误放会报 1046 未选库」。
- **但 2 位基础 schema 文件并未同步包含 018/019 的变更**（如 `payment_attempts.amount_minor/currency_code`、`order_items.order_item_no`、`transactions.payment_no/refunded_minor`、`transaction_refunds`/`refunds` 新列）。
- **后果（已在本会话实证）**：经 `reset.sh` 建出的库缺少上述列 → 下单报 `Unknown column 'order_item_no'`（原「神秘 500 故障」之一），审计夹具注入报 `1364 Field 'amount_minor' doesn't have a default`。即：**基础 schema 与增量迁移长期分叉，reset 出的环境不可用**。
- **建议**：将 018/019（及未来 3 位迁移）的变更合并回 `01-*`~`10-*` 基础 schema，使「重放 2 位文件 = 最终表结构」这一隐含契约成立；或将 reset 改为「2 位建基 + 3 位增量统一重放（修正 1046 选库问题）」。

### F2（轻微）— Spec 008 缺号
`docs/specs/` 目录序列为 `001–007`、`009–022`，**008 缺失**（007→009 直接跳号）。ADR 索引中「Feature 008 = settlement」却映射到 spec `007-settlement`，存在 Feature 编号与 spec 目录编号的偏移。建议补 008 占位或修正编号映射，避免读者误判。

### F3（轻微）— Spec 015 重号 ✅ 已修复（2026-09-08）
原存在两个 `015-*` 目录：
- `015-multi-channel-payment`（对应 ADR-0064，Feature 015：一交易多支付单）— **合法 015**
- `025-snowflake-business-no`（对应 ADR-0062，业务单号雪花算法）— 孤儿误标

2026-09-08 已将孤儿 spec 重命名为 **`025-snowflake-business-no`**（下一可用空号，无外部引用，重命名零风险），其 `spec/plan/acceptance/tasks` 内部自引同步更新。重号已消除。

### F4（轻微）— 缺 `docs/specs/README.md` 索引
22 个 spec 目录无顶层索引文件，不利于按 Feature 快速定位（与 ADR 索引的高完成度形成反差）。建议新增 specs 索引，列明每个 spec 的状态/关联 ADR。

### F5（轻微 · 夹具漂移）— 审计夹具未随 018 迁移更新
`deployment/demo/fixtures/audit/audit-faults.sql` 两处 `payment_attempts` INSERT 省略 `amount_minor`/`currency_code`（018 迁移后已 `NOT NULL`），导致 `scenario-audit.sh` 在注入步骤即失败（1364）。审计**引擎**正常（用补齐列的修正夹具注入后，审计批正确检出 5 条差异）。建议同步更新夹具以恢复 demo 一键可跑。

### F6（轻微）— 压测报告生成器 schema 不匹配
`deployment/performance/generate-report.js` 假定目录压测 JSON（`data.scenarios.sku_cache_read`），对全链路 JSON（`data.throughput`）读取 `undefined` 而崩溃，无法产出链路 HTML 报告。属脚本健壮性问题，不影响数据。

---

## 3. 文档与开发进度匹配度

`docs/architecture/roadmap.md` 对本环境是否「实跑」保持**高度诚实**：

- **明确记录的偏离**（已收口，ADR-0053）：
  - `013-inventory-reservation` / `014-seckill-and-cache` **代码先行、后补 spec/ADR**（2026-08-31 收口）。
  - `014` 的 Redis 引入**未经 roadmap §7「压测基线→论证引入」闸门**（ADR-0044 偏离），k6 基线 + 论证证据列为 TODO。
- **明确记录「本环境未实跑」**（roadmap 第 23 行原文）：
  > 全栈压测（`performance/catalog-seckill-k6.js`）本环境未实跑（Docker/MySQL 不可用）。
  - **本次验证已部分填补该缺口**：以 Node 零依赖 loadgen 等价复刻，完成了目录缓存读、秒杀闪购与全链路压测（见业务验证报告 §3）。但 k6 本身仍因代理拦截二进制而无法运行——roadmap 该条「未实跑」状态在严格意义上仍成立（k6 路径未跑），建议将措辞更新为「k6 脚本未跑，已由 Node loadgen 等价覆盖」。

- **Feature 进度覆盖**：roadmap 列明 001/003/004/005/006/007/009/010/011/012/013/014/015/016 均具完整 Spec/Plan/Tasks/Acceptance 且已落地；017（会计审计，ADR-0065）2026-09-07 实现、450 测试全绿；018/019（schema 规范化 / order 驱动退款）Accepted→Implemented；020/021/022 spec 已存在。与代码实际状态**基本一致**。

---

## 4. 组织合理性评估

| 项 | 评价 |
|---|---|
| 文档分层 | ✅ 优秀，`Constitution>ADR>方案>系统>Roadmap>Spec` 边界清晰，冲突优先级写明 |
| ADR 治理 | ✅ 优秀，状态机完整（Proposed/Accepted/Rejected/Not Implemented/Superseded/Deprecated），永不删除只演进 |
| 偏差透明度 | ✅ 罕见地好，roadmap 与 ADR 索引均主动披露冲突（如 ADR-0054 双文件同号备案、014 Redis §7 偏离）|
| 编号一致性 | ⚠️ 有瑕疵（spec 008 缺、015 重、ADR 文件号与 ADR 号解耦需查表）|
| 可运维性 | ❌ 关键缺口（reset.sh 迁移漂移，见 F1）|
| 一次性报告归档 | ✅ 符合规范（本报告即归入 `docs/archive/audits/`）|

**总体**：文档组织**合理且超出一般项目水准**，核心风险不在「写没写」而在「reset 脚本与迁移脚本分叉」这一运维一致性漏洞。

---

## 5. 建议（按优先级）

1. **【高】修复 reset.sh 迁移漂移（F1）**：合并 018/019 至基础 schema，或改为「基础+增量统一重放并修正选库」，确保任意时刻 `reset.sh` 产出可用库。这是消除反复「神秘 500 / 1364」的根本手段。
2. **【中】校准 spec 编号（F2/F3）**：补 008 或修正映射；拆分重号的 015。
3. **【中】新增 `docs/specs/README.md` 索引（F4）**，与 ADR 索引对齐。
4. **【低】同步审计夹具与 018 迁移（F5）**、修复压测报告生成器 schema（F6），恢复 demo 一键可跑与链路 HTML 产出。
5. **【低】更新 roadmap「全栈压测未实跑」措辞**，反映 Node loadgen 等价覆盖的事实，避免后续读者误判进度。

---

## 6. 结论

项目文档体系**结构完整、层级合理、且具备行业少见的偏差自我披露能力**，整体与开发进度匹配良好（Feature 覆盖、ADR 状态、roadmap 偏离均一致）。主要改进点为：(1) **reset.sh 与增量迁移的分叉**这一关键运维风险须收口；(2) spec 编号连续性与顶层索引的少量瑕疵。上述均为**文档/运维一致性**问题，**不涉及产品功能缺陷**。
