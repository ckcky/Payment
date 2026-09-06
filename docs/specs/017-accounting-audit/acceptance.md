# Feature 017 — accounting-audit 验收（acceptance）

**版本**：0.1
**日期**：2026-09-06
**状态**：⏸ **待执行**——本 Feature 当前仅文档，后端 `/internal/audit/**` 与演示页尚未落位（负责人指示：先把 spec 文档搞好）
**关联**：[spec.md](spec.md)（SC-001~018 权威清单）· [plan.md](plan.md)（测试策略 §8、模拟数据 §8.2、演示控制台 §10）· [tasks.md](tasks.md) · [audit-console-mockup.html](audit-console-mockup.html)（离线原型）

---

## 1. 验收方式（四层）

| 层 | 怎么验 | 什么时候能跑 | 对应 |
|---|---|---|---|
| **L1 构建门禁** | `mvn -o clean verify -fae` 全绿（含 architecture-tests ArchUnit 边界） | 每批任务完成后 | SC-007 |
| **L2 自动化测试** | 域层单测（T1）+ H2 集成测试（T2）+ API 契约测试（T4），用例矩阵见 §3 | 批次 C~G 交付后 | SC-001~016 |
| **L3 端到端脚本** | `bash deployment/demo/scenario-audit.sh`（真实 docker MySQL + 全服务），断言清单见 §4 | 批次 B/I 交付后 | SC-001~015、018 |
| **L4 演示页冒烟** | `http://localhost:8091/audit.html` 全流程点击（MOCK 离线 / LIVE 真连），清单见 §5 | 批次 I 交付后 | SC-017 |

**当前可执行的只有"文档评审 + 原型演示"**：`audit-console-mockup.html` 可直接用浏览器打开（纯前端、不连任何服务），按 §5 的步骤走一遍即可看到完整交互。

---

## 2. 模拟数据（fixture）一览

> 定义见 [plan.md §8.2](plan.md#82-模拟数据集fixture设计)。单号与金额固定，每次执行结果完全一致。

| ID | 内容 | 期望差异 | 用于 |
|---|---|---|---|
| **F1** | 平账组：4 业务事实（PM-AUD-0001/0002、RF-AUD-0001、SB-AUD-0001）+ 4 posting 一一对应 | 0 条，batch=`BALANCED` | TC-01、演示「无差异」路径 |
| **F2** | 漏记账：PM-AUD-0003 SUCCEEDED 8000，账本无 | `MISSING_POSTING`（8000 / 0） | TC-02、SC-001、挂账演示 |
| **F3** | 孤儿分录：posting `PM-AUD-GHOST1` 5000，业务无 | `ORPHAN_POSTING`（0 / 5000） | TC-03、SC-002 |
| **F4** | 金额不符：PM-AUD-0001 账本 10000 → 9900 | `AMOUNT_MISMATCH`（10000 / 9900） | TC-04、SC-003 |
| **F5** | 重复记账：(PAYMENT, PM-AUD-0002) 两条 posting | `DUPLICATE_POSTING`（25000 / 50000） | TC-05、SC-003 |
| **F6** | 科目记错：手续费 100 从 `PLATFORM_FEE_REVENUE` 挪到 `MERCHANT_PAYABLE`（借贷仍平衡） | `ACCOUNT_RECON_BREAK` | TC-06、SC-005 |
| **F7** | 跨账不符：settlement_items 合计 21750 → 21000 | `CROSS_LEDGER_MISMATCH` | TC-07、SC-003 |
| **F8** | 账实不符：渠道账单多 `CH-AUD-X1` 12000；`CH-AUD-0002` 金额 25000 vs 24500 | `LEDGER_VS_STATEMENT_BREAK` + 金额不符 | TC-08 |
| **F9** | 账表不符：报表净额与回算差 500 | `REPORT_MISMATCH` | TC-09 |
| **F10** | 全故障混合（F2~F9 全注入） | 9 条差异，batch=`HAS_DIFFERENCE` | 演示默认数据集 |

**注入方式**

- 测试内（L2）：`AuditFixture` builder 构造域对象，不碰 DB；
- 真环境（L3）：`deployment/demo/fixtures/audit/audit-faults.sql`（幂等，首行强制 localhost 校验）+ 周期渠道账单 `{period}.csv`；
- 演示页（L4）：MOCK 模式内置同一份 fixture（JS 常量），纯前端执行比对算法。

---

## 3. 测试用例矩阵（L2）

| TC | 层 | 输入 fixture | 断言 | 对应 SC |
|---|---|---|---|---|
| TC-01 | 集成 | F1 | 0 差异，`status=BALANCED`，`checked_count`=4 | SC-004 |
| TC-02 | 集成 | F2 | 1×`MISSING_POSTING`，expected=8000 / actual=0 | SC-001 |
| TC-03 | 集成 | F3 | 1×`ORPHAN_POSTING` | SC-002 |
| TC-04 | 集成 | F4 | 1×`AMOUNT_MISMATCH` | SC-003 |
| TC-05 | 集成 | F5 | 1×`DUPLICATE_POSTING` | SC-003 |
| TC-06 | 集成 | F6 | 1×`ACCOUNT_RECON_BREAK`，`MERCHANT_PAYABLE` 勾稽差 100 | SC-005 |
| TC-07 | 集成 | F7 | 1×`CROSS_LEDGER_MISMATCH` | SC-003 |
| TC-08 | 集成 | F8 | `LEDGER_VS_STATEMENT_BREAK` + 金额不符各 1 | — |
| TC-09 | 集成 | F9 | 1×`REPORT_MISMATCH` | — |
| TC-10 | 集成 | F10 | 9 条差异，kind 齐全，`status=HAS_DIFFERENCE` | — |
| TC-11 | 集成 | F1 重复触发同 period+scope | 回查同一 batch，不新增批次 / 差异 | SC-006 |
| TC-12 | 单元 | 各类差异判定（金额 0 / 负数 / 币种不一致 / 方向相反） | 分类正确，不抛异常 | FR-002 |
| TC-13 | 单元 | `PENDING` 事实 | 不计为差异 | FR-012 |
| TC-14 | 集成 | F2 挂账 | 平衡分录（借 CUSTOMER_CASH 8000 / 贷 SUSPENSE 8000），`posting_no` 非空，差异 `SUSPENDED`，业务状态未变 | SC-008 |
| TC-15 | 集成 | 已挂账 → `TRANSFER` | `SUSPENSE` 余额归 0，勾稽通过 | SC-009、SC-016 |
| TC-16 | 集成 | `SUPPLEMENT` → recheck | 差异消失并置 `VERIFIED` | SC-010 |
| TC-17 | 集成 | `REVERSE` 红冲 | 原分录仍在（`ledger_entries` 只增不减），source 净额归 0 | SC-011 |
| TC-18 | 单元 | 调账金额 > 差异剩余额 | 拒绝 `ADJUST_AMOUNT_EXCEEDED`，无分录 | SC-012 |
| TC-19 | 集成 | 同一 `adjust_no` 重复提交 | 返回首次结果，账上仅 1 条 posting | SC-013 |
| TC-20 | 单元 | `WRITE_OFF` 无 reviewer / operator==reviewer / >¥100 无 reviewer | 全部拒绝 | SC-014 |
| TC-21 | 集成 | 有 `PENDING` 差异时关批 / 全部 VERIFIED 后关批 | 400 / 200 `CLOSED` | SC-015 |
| TC-22 | 集成 | 任意处置序列后查试算平衡 | Σ(借−贷)=0 | SC-018 |
| TC-23 | 架构 | ArchUnit | reconciliation 可依赖 ledger/payment/settlement client；**ledger MUST NOT 反向依赖** | NFR-007 |
| TC-24 | 契约 | MockMvc 八个 `/internal/audit/**` 端点 | 参数校验 / 404 / 400 / 409 与响应字段齐全 | FR-021 |

---

## 4. 端到端脚本断言清单（L3 · `scenario-audit.sh`）

| 步 | 动作 | 断言 |
|---|---|---|
| ① | 灌 `audit-faults.sql`（F2~F9） | 脚本幂等；仅 demo 库可执行 |
| ② | `POST /internal/audit/batches {period, scope=CERTIFICATE}` | 200，`batch_no` 以 `AB` 开头，status 落 `HAS_DIFFERENCE` |
| ③ | `GET /internal/audit/batches/{batchNo}/differences` | 差异数 == fixture 期望值，且包含 `MISSING_POSTING` / `ORPHAN_POSTING` |
| ④ | 关批（有未处置差异） | **400** 被拒（门禁生效） |
| ⑤ | 对 F2 差异 `POST .../suspend` | 200，返回 `posting_no`，差异 status=`SUSPENDED` |
| ⑥ | 对 F2 差异 `POST .../adjust {kind=SUPPLEMENT}` | 200，`SUSPENSE` 归零 |
| ⑦ | `POST /internal/audit/batches/{batchNo}/recheck` | 该差异消失 / 置 `VERIFIED` |
| ⑧ | 其余差异逐条处置（脚本循环） | 全部 `VERIFIED` |
| ⑨ | 关批 | 200 → `CLOSED` |
| ⑩ | `GET /internal/ledger/balance` | 各币种差额为 0（调账未破坏借贷平衡） |

脚本纪律（ADR-0051）：只编排不伪造、断言失败即非零退出；`bash -n` 通过；不得直接写业务状态或跳过状态机。

---

## 5. 演示页冒烟清单（L4 · `audit.html`）

**MOCK 模式（离线，原型 `audit-console-mockup.html` 现在就能走）**

1. 选 scope=`ALL`、数据集=全故障组 → 点「触发对账任务」
2. 观察执行日志逐步滚动：拉事实 N 条 → 拉分录 M 条 → 双向比对 → 科目勾稽 → 产出差异 → 状态落定
3. 结果区出现 9 条差异（含 `MISSING_POSTING` / `ORPHAN_POSTING` / `AMOUNT_MISMATCH` / `DUPLICATE_POSTING` / `ACCOUNT_RECON_BREAK` / `CROSS_LEDGER_MISMATCH` / `LEDGER_VS_STATEMENT_BREAK` / `REPORT_MISMATCH`）
4. 选中 `MISSING_POSTING` → 点「挂账到 SUSPENSE」→ 看到分录预览（借 客户资金 ¥80.00 / 贷 待处理差错款 ¥80.00）与 `posting_no`，差异转 `SUSPENDED`
5. 选 `TRANSFER` 或 `SUPPLEMENT` → 提交调账 → 看到 `posting_no`、`SUSPENSE` 余额归零、差异转 `ADJUSTED`
6. 点「重新核对」→ 该差异置 `VERIFIED`
7. 直接点「关闭批次」→ 红字提示 400「尚有 N 条未收口差异」
8. 处置完剩余差异 → 关闭成功 `CLOSED`
9. 底部台账：`audit_adjustments` 每笔可追溯（操作人 / 复核人 / 原因 / posting_no）；试算平衡 Σ=0 ✅

**LIVE 模式（后端交付后）**：切到 LIVE → 重复上述 1~9，数据来自真实 `/proxy/recon/internal/audit/**`；若后端未交付，页面应明确报出 404 而非静默失败（NFR-008）。

---

## 6. DoD（完成定义）

- [ ] 批次 B~I 任务全部完成，`tasks.md` 勾选回填
- [ ] **L1** `mvn -o clean verify -fae` 全绿（含 architecture-tests）
- [ ] **L2** TC-01~TC-24 全部通过（H2 MySQL 兼容模式）
- [ ] **L3** `scenario-audit.sh` ①~⑩ 全绿；既有 `scenario-reconciliation.sh` **无回归**（NFR-006）
- [ ] **L4** `audit.html` MOCK 与 LIVE 双模式均走通 §5 九步
- [ ] ADR-0055 已立项并注册；`spec.md` / `plan.md` 状态 → Accepted
- [ ] `docs/architecture/systems/reconciliation-service.md`、`technical-solution.md`、004 SC-005 标注、`roadmap.md` 均已同步
- [ ] 提交并 merge 到 master

---

## 7. 当前状态（2026-09-06）

| 项 | 状态 |
|---|---|
| `spec.md` / `plan.md` / `tasks.md` / `acceptance.md` | ✅ 已编写（Draft，待拍板） |
| 界面原型 `audit-console-mockup.html` | ✅ 已交付（文档附件，离线可点） |
| 后端 `/internal/audit/**` | ⏸ 未开始（批次 C~G） |
| 模拟数据 fixture（代码 / SQL / CSV） | ⏸ 未开始（批次 H） |
| `audit.html` 落位 + `scenario-audit.sh` | ⏸ 未开始（批次 I） |
| 构建门禁 / 自动化 / E2E / 冒烟 | ⏸ 均待后端交付后执行 |
