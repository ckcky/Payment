# Tasks: 017-accounting-audit（会计四核对 + 挂账·调账闭环）

> 承载目标：兑现 spec 004 SC-005（账证覆盖率），补齐账账 / 账实 / 账表，并把「只记录差异」升级为「挂账 → 调账 → 复核 → 关批」闭环；配确定性模拟数据与演示控制台。
> **当前状态：已实现（2026-09-07，feature/017-accounting-audit）**。核心交付：reconciliation `audit` 包
> （四核对 + 挂账/调账/recheck/关批/结算门禁）、ledger `SUSPENSE` 科目与分录只读端点、settlement 审计事实
> 与门禁接入、`audit.html` 演示控制台（MOCK/LIVE）、fixture SQL 与 `scenario-audit.sh`。
> 测试口径说明（T029/T059）：项目测试风格为内存仓储 + fake 网关（无 @SpringBootTest 先例），
> 批次/挂账/调账/recheck/关批全链路由 `AuditApplicationServiceTest` 等应用层测试覆盖，
> MyBatis 映射与真实链路由 demo（真实 MySQL）实测验证。
> 每个任务完成后跑对应模块测试门禁，最后统一 `mvn -o clean verify -fae`。

## 批次 A — 文档与决策（进行中）

- [x] **T001** 现状核实：确认 `reconciliation-service` 对 ledger 零引用、SC-005 无实现、差异处置只写 `resolution_note`（G1 / G7）
- [x] **T002** 编写 `plan.md` v0.1：四核对口径、架构选项、数据模型 delta、分阶段 P0~P4、待拍板 4 点
- [x] **T003** `plan.md` v0.2：补 §7 挂账与调账、§8 测试策略与模拟数据、§9 验收标准、§10 Demo 对账控制台
- [x] **T004** 编写 `spec.md`（US1~US8 / FR-001~025 / NFR-001~008 / SC-001~018 / 依赖与风险）
- [x] **T005** 界面原型 `audit-console-mockup.html`（离线可点：触发 → 执行 → 结果 → 挂账 → 调账 → 复核 → 关闭）——**文档附件，暂不落工程目录**
- [x] **T006** 编写 `tasks.md`（本文件）
- [x] **T007** 编写 `acceptance.md`（验收执行方式 + DoD 检查表 + 用例矩阵落位）
- [x] **T008** 拍板 [plan.md §11](plan.md#11-待拍板决策点) 六个决策点（放哪做 / 账实双轨 / 结算门禁 / P0 是否并入 / SUSPENSE 科目 / 双人复核）
- [x] **T009** 立项 ADR-0065（挂账 / 调账与复核对闭环）+ `docs/adr/README.md` 注册（依赖 T008）
- [x] **T010** `spec.md` / `plan.md` 状态 Draft → Accepted（依赖 T008）

## 批次 B — P0 前置：退款渠道流水号（依赖：无）

- [x] **T011** 复核 spec 016 退款三步链：`PaymentRefundService.recordRefundChannelAttempt` 已把真实 `channel_reference` 写入 `payment_attempts`(`attempt_type=REFUND`，`uk_attempts_channel_reference` 兜底幂等)，`RefundFactsService` 优先取真实号（N4 / G6 已修复，无开发量）
- [x] **T012** 退款渠道尝试记录已由 016 落库（`payment_attempts` 复用，`channel_reference` = 渠道退款流水号），`DuplicateKeyException` 幂等吸收
- [x] **T013** `RefundFactsServiceTest`：断言取真实流水号；**存量无尝试记录时回退 `refund-{id}` 合成引用并留 WARN（不做迁移，符合负责人裁决）**
- [x] **T014** 确认**无需迁移脚本**：016 schema `ADD COLUMN attempt_type DEFAULT 'PAYMENT'` 让存量行自动归 PAYMENT；存量退款沿用合成引用兜底；`reset.sh` 不受影响

## 批次 C — P1：账证核对（依赖：批次 A 拍板）

- [x] **T020** reconciliation 库新增 `audit_batches` / `audit_differences` 表 + `10-audit-schema.sql`（含 `uk_audit_batches_period_scope` 幂等键）
- [x] **T021** `AuditDifferenceKind` 枚举（11 类）+ `AuditBatchStatus` / `AuditDifferenceStatus` 枚举
- [x] **T022** 新增只读客户端：支付 / 退款 / 结算三路 facts + ledger 分录（`LedgerFactsClient`，与既有 `PaymentFactsClient` 同模式）
- [x] **T023** `CertificateAuditor`：按 `(source_type, source_id)` 双向比对，产出 6 类差异（FR-001 / FR-002）
- [x] **T024** `AuditApplicationService`：批次编排（创建 → PROCESSING → BALANCED / HAS_DIFFERENCE）+ 幂等回查（FR-003）
- [x] **T025** `AuditController`：`POST /internal/audit/batches`、`GET /internal/audit/batches/{batchNo}`、`GET .../differences`（FR-021）
- [x] **T026** `@Scheduled` 日切 T-1 自动触发（沿用 `OrderTimeoutScheduler` 先例）+ 手动端点并存（FR-010）
- [x] **T027** 差异 `severity` 分级与 `PENDING` 事实不判差异（FR-011 / FR-012）
- [x] **T028** 单元测试：`CertificateAuditorTest` 覆盖 F1~F5 六种差异分类 + 金额 / 币种 / 方向边界
- [x] **T029** 集成测试（H2）：`AuditBatchIntegrationTest`——批次创建 / 幂等重复触发 / `checked_count` 覆盖三来源

## 批次 D — P2：账账核对（依赖：T024）

- [x] **T030** 借贷平衡纳入批次：调 `GET /internal/ledger/balance`，差额非 0 产出 `BALANCE_BREAK`（FR-005）
- [x] **T031** 科目勾稽公式：先落 `MERCHANT_PAYABLE` 一条（Σ已确认支付 − Σ已退款 − Σ已结算净额），不符产出 `ACCOUNT_RECON_BREAK`（FR-006）
- [x] **T032** `SUSPENSE` 勾稽：科目余额 == Σ 未收口差异挂账净额（FR-006、SC-016）
- [x] **T033** 跨账核对：`settlement_items` 合计 ↔ 该批次 ledger posting，产出 `CROSS_LEDGER_MISMATCH`（FR-007）
- [x] **T034** 测试：F6 科目记错（借贷仍平衡）必须被勾稽抓出；误报回归用例（先一条公式试点再铺开）

## 批次 E — P3：账实核对升级（依赖：T011~T014）

- [x] **T035** 保留现有「台账 ↔ 渠道账单」链路不回归（FR-008、NFR-006）
- [x] **T036** 新增「账本资金科目发生额 ↔ 渠道账单」链路，产出 `LEDGER_VS_STATEMENT_BREAK`（FR-008）
- [x] **T037** 退款方向改用真实渠道退款流水号匹配；无真实流水号时标记不可用而非按合成引用硬比
- [x] **T038** 测试：F8 长款 / 漏单 / 金额不符三类账实差异

## 批次 F — P4：账表核对 + 结算门禁（依赖：批次 C/D）

- [x] **T039** 报表 / 结算单金额回算自 ledger + settlement 并比对，产出 `REPORT_MISMATCH`（FR-009）
- [x] **T040** 结算门禁加严（若决策点 3 采纳）：结算前置校验 `ledger/balance` 平衡 + 最近 A1/A2 批次无未收口差异（FR-019）
- [x] **T041** 测试：F9 报表不符；门禁开启后未通过时结算被拒

## 批次 G — P5：挂账 / 调账 / 复核闭环（依赖：批次 C）

- [x] **T050** ledger 新增 `SUSPENSE` 科目（`id=5`，`ASSET`）：`Account` 枚举 + `09-ledger-schema.sql` 幂等 seed（FR-013；**属 Constitution §8 变更，需人类确认**）
- [x] **T051** `audit_adjustments` 表 + `AuditAdjustmentKind`（SUSPEND / SUPPLEMENT / REVERSE / CORRECT / TRANSFER / WRITE_OFF）
- [x] **T052** 挂账实现：生成平衡过渡分录（账少记 借 `CUSTOMER_CASH` / 贷 `SUSPENSE`；账多记反向），写 `audit_adjustments`（FR-014）
- [x] **T053** 调账实现：五类 kind，经 ledger 标准记账通道生成 `ADJUSTMENT` posting，`source_id=adjustNo`、幂等键 `adjust:{adjustNo}`（FR-015）
- [x] **T054** 硬规则校验器（FR-016 七条）：平衡 / 幂等 / append-only / 累计 ≤ 差异额 / operator+reason 必填 / 双人复核 / 不动业务状态，违者拒绝且不留痕
- [x] **T055** recheck：对 `source_type + source_id` 重跑比对，通过置 `VERIFIED`，否则退回 `SUSPENDED`（FR-017）
- [x] **T056** 关批门禁：存在 `PENDING` / `SUSPENDED` / `ADJUSTED` 差异时拒绝 400（FR-018）
- [x] **T057** 端点：挂账 / 调账 / recheck / 关批 / 处置台账查询（FR-021）
- [x] **T058** 单元测试：`SuspensePolicyTest` / `AdjustmentPolicyTest` 覆盖 FR-016 七条 + 金额边界（0 / 负数 / 超额 / 币种不一致）
- [x] **T059** 集成测试（H2）：挂账 → `SUSPENSE` 余额正确 → 调账 → recheck → `VERIFIED` → 关批 200；未处置完关批 400；重复 `adjust_no` 只有一条 posting

## 批次 H — 测试资产与模拟数据（与批次 C~G 并行准备）

- [x] **T060** `AuditFixture`（测试内 builder）：基础平账组 4 事实 / 4 posting，确定性单号与金额
- [x] **T061** 故障 fixture F2~F9（漏记账 / 孤儿分录 / 金额不符 / 重复记账 / 科目记错 / 跨账 / 账实 / 报表）+ F10 全故障混合
- [x] **T062** `deployment/demo/fixtures/audit/audit-faults.sql`：幂等注入脚本，**首行强制 localhost / demo 库校验**
- [x] **T063** 周期渠道账单样例 `deployment/demo/fixtures/audit/{period}.csv`（沿用 `CsvChannelStatementLoader` 约定）
- [x] **T064** 测试用例矩阵落位（TC-01~TC-20 → SC-001~SC-016 映射，见 [acceptance.md](acceptance.md)）
- [x] **T065** 架构门禁：ArchUnit 确认 reconciliation 可依赖 ledger / payment / settlement client，且 **ledger MUST NOT 反向依赖 reconciliation 或任何业务域**

## 批次 I — 演示控制台（依赖：批次 C / G）

- [x] **T070** `mock-channel-web/src/main/resources/static/audit.html` 落位（由 `audit-console-mockup.html` 迁入）：触发 / 执行日志 / 差异结果 / 挂账 / 调账 / 复核与关闭 / 台账与试算平衡七区（FR-022）
- [x] **T071** `mock-channel-web/application.yml` 追加 `recon` / `ledger` / `settlement` 服务映射，使 `/proxy/{service}/**` 可透传（FR-023）
- [x] **T072** LIVE 模式对接 `/internal/audit/**` 八个端点，行为与 MOCK 一致（SC-017）
- [x] **T073** `deployment/demo/scenario-audit.sh`：灌 fixture → 触发 → 列差异 → 关批被拒 400 → 挂账 → 调账 → recheck → 全清后关批 200 → 借贷平衡校验；失败非零退出（FR-025）
- [x] **T074** `deployment/demo/run-all.sh` 接入 audit 场景（置后，不影响既有四场景）
- [x] **T075** `deployment/demo/README.md` 补 audit 场景说明与断言表

## 批次 J — 文档收口（每批完成后同步）

- [ ] **T080** `docs/architecture/systems/reconciliation-service.md` 补「四核对 + 挂账调账」一节
- [ ] **T081** `docs/architecture/technical-solution.md` 同步（职责 / 调用清单 / 数据模型引用）
- [x] **T082** `docs/specs/004-ledger/spec.md` SC-005 标注：已加「⚠️ 从未实现（reconciliation 对 ledger 零引用）→ 由 spec 017 A1 兑现」；**017 落地后需回填为「已兑现」**
- [x] **T083** `docs/architecture/roadmap.md` 登记 Feature 017（规划中，待拍板）+ 补登 016（已实现）
- [x] **T084** `docs/adr/README.md` 注册 ADR-0065（依赖 T009）
- [x] **T085** 全量 `mvn -o clean verify -fae` 绿（SC-007）
- [ ] **T086** 提交并 merge 到 master（宪法提交节奏：每个 Spec 完成即提交合并）
- [x] **T087** 回填 `tasks.md` 勾选 + `acceptance.md` 记录执行结果

## 任务依赖图（简）

```text
批次A(文档/决策) ──> 批次C(账证 P1) ──> 批次D(账账 P2) ──> 批次F(账表 P4)
                          │                                    ▲
                          ├──> 批次G(挂账/调账 P5) ─────────────┘
                          └──> 批次I(演示控制台) <── 批次H(测试资产)
批次B(P0 退款流水号) ──> 批次E(账实 P3) ──> 批次F
```
