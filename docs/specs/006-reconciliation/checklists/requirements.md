# Requirements Checklist: Reconciliation 对账（缺口补齐）

**Purpose**: 需求质量校验 —— 确认 spec.md 的需求完整、无歧义、可测试，且与真实代码现状一致。
**Created**: 2026-08-29
**Feature**: [spec.md](../spec.md)

**Note**: 标记 `[x]` 表示 reviewer 已确认该需求质量项满足；`[ ]` 表示待确认/阻塞。

## 完整性

- [x] CHK001 每条 FR 都可映射到至少一个用户故事 / 验收场景（FR-001~FR-021 ↔ US1~US4）
- [x] CHK002 金额铁律在 FR-005 显式声明（禁 float/double，long 分或 BigDecimal），且不变量落在 data-model INV-1
- [x] CHK003 幂等要求在 FR-001（周期幂等）、FR-007（重复处理）、FR-010（重复关闭）显式声明，覆盖三类重复
- [x] CHK004 原始事实不回写在 FR-002 / SC-005 显式声明，并可验证（N1 相关：`confirmed-facts` 为只读 GET）
- [x] CHK005 四项已核实缺口（G1 生命周期未接线 / G2 账单忽略 period / G3 无 RPC 弹性 / G4 文档漂移）均有对应用户故事与任务（US1~US4 + Phase 3~7）
- [x] CHK006 Spec 明确声明为「缺口补齐型」，未把已实现能力写成待建（能力现状矩阵逐项标注）

## 一致性

- [x] CHK007 与 Constitution §III 边界 #4（Reconciliation ≠ Settlement）一致：`settlement-summary` 仅输出匹配事实，批次关闭不触发资金动作
- [x] CHK008 与 Constitution §IV 一致：全部交互为同步 RPC + 幂等，未引入 MQ；重试用 OpenFeign 自带 `Retryer`，未引入 Resilience4j
- [x] CHK009 与 Constitution §V.2 一致：状态迁移仍集中在 `ReconciliationBatch` 四方法，未新增 setStatus
- [x] CHK010 与 Constitution §V.6 一致：FR-012 显式配置超时（修复当前「无超时」的既存违反）
- [x] CHK011 与 Constitution §VII 一致：FR-011 补齐 `FINANCIAL_AUDIT`，指标覆盖「对账差异数量/金额」
- [x] CHK012 与 `settlement-service` 现有实现一致：`unresolvedDifferenceCount` 与 `SettlementEligibility.java:33` 同源同值（INV-12）
- [x] CHK013 与 Roadmap Phase 6「包含/不包含」一致：真实渠道接入、自动调账、复杂会计处理、契约加 `period` 参数均在 Out of Scope

## 可测试性

- [x] CHK014 SC-001~SC-008 均为可度量结果（幂等率 / 门禁拒绝率 / 零回写 / 重试上限）
- [x] CHK015 每个 US 均有 Independent Test 与 Acceptance Scenarios（Given/When/Then）
- [x] CHK016 每个 US 在 tasks.md 中有独立的测试任务与实现任务（T011~T041）

## 现状核实（防止写出「幻想式」需求）

- [x] CHK017 已核实对账核心链路已实现（匹配/状态机/持久化/周期幂等/结算汇总/指标/测试），Spec 未按绿地项目描述
- [x] CHK018 已核实 `beginProcessing()`/`close()` 在应用层零调用（`ReconciliationApplicationService.java:104-115` 无状态推进，controller 无 close 端点）
- [x] CHK019 已核实 `CsvChannelStatementLoader.load(period)` 忽略参数（`:24/:27` 固定 `sample.csv`）
- [x] CHK020 已核实两个 Feign 客户端无超时/重试配置（`PaymentFactsFeignClient.java:11`、`RefundFactsFeignClient.java:11`、`application.yml:22-26`）
- [x] CHK021 已核实平台侧事实端点无 period 过滤（`PaymentFactsService.java:28`、`RefundFactsService.java:26`）—— 记为 N1，未假装「只改 loader 就能按周期」
- [x] CHK022 已核实对账侧无 `FINANCIAL_AUDIT`（`grep FINANCIAL_AUDIT reconciliation-service/src/main` 无命中）—— 记为 N2
- [x] CHK023 已核实 `ReconciliationBatchResponse` 不含未处理差异数（`:36-47`）—— 记为 N4
- [x] CHK024 已核实重复 reference 折叠（`ReconciliationMatching.java:54-72` 用 `Map.put`）—— 记为 N5 并标 `[待定]`，未静默改匹配语义

## 未决项（待 ADR 确认，实现阻塞）

- [ ] CHK025 ADR-0019（批次差异处理生命周期：接线方式 / 关闭门禁 / 幂等语义）经负责人确认（Constitution §8.3/§8.8）
- [ ] CHK026 ADR-0020（渠道账单来源：按周期 fixture + 回退 vs 参数化加载器 vs 维持全局）经负责人确认
- [ ] CHK027 ADR-0021（事实读取弹性：超时/有限重试 vs 引入熔断中间件）经负责人确认
- [ ] CHK028 N1（平台事实周期口径）是否另立 ADR 改 `confirmed-facts` 契约（Constitution §8.4），由负责人裁定
- [ ] CHK029 N5（重复 reference 差异类型）是否纳入后续 Feature，由负责人裁定
- [ ] CHK030 `technical-solution.md:105`「骨架」与 `roadmap.md` 状态标注的统一口径由负责人确认（FR-021 / T046）

## Notes

- 标记 `[x]` 仅表示需求质量已审，不代表实现完成。
- ADR 未确认前不得进入实现阶段（Constitution §VIII.6）。
