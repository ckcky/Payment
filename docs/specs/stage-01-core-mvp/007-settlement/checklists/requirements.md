# Requirements Checklist: Settlement 结算（缺口补齐）

**Purpose**: 需求质量校验 —— 确认 spec.md 的需求完整、无歧义、可测试，且与真实代码现状一致。
**Created**: 2026-08-29
**Feature**: [spec.md](../spec.md)

**Note**: 标记 `[x]` 表示 reviewer 已确认该需求质量项满足；`[ ]` 表示待确认/阻塞。

## 完整性

- [x] CHK001 每条 FR 都可映射到至少一个用户故事 / 验收场景（FR-001~FR-024 ↔ US1~US3）
- [x] CHK002 金额铁律在 FR-004 显式声明（禁 float/double，全程 `long` 分），不变量落在 data-model INV-1
- [x] CHK003 幂等要求在 FR-002（调整项幂等键）、FR-011（三处唯一约束）、FR-012（跨商户/周期错配）显式声明
- [x] CHK004 「无真实出款」硬约束在 FR-020 与 INV-12 显式声明并可代码级验证
- [x] CHK005 五条已核实缺口（G1 调整项 / G2 闸门 / G3 混币种 / G4 记账 / G5 文档漂移）均有对应用户故事与任务
- [x] CHK006 Spec 明确声明为「缺口补齐型」，未把已实现能力写成待建（能力现状矩阵逐项标注）

## 一致性

- [x] CHK007 与 Constitution §II.1 一致：调整项、净额、明细全程 `long` 分，无 float/double
- [x] CHK008 与 Constitution §II.3 一致：记账交由 `ledger-service`，settlement 不自建账本（ADR-0023）
- [x] CHK009 与 Constitution §III 边界 #4 一致：settlement 只消费 `settlement-summary`，零回写（FR-010 / INV-15）
- [x] CHK010 与 Constitution §IV 一致：同步 RPC + 幂等，重试用 OpenFeign 自带 `Retryer`，不引 MQ / Resilience4j
- [x] CHK011 与 Constitution §V.6 一致：FR-019 显式配置超时（修复当前「无超时」的既存违反，N4）
- [x] CHK012 与 Constitution §V.7 一致：闸门本地校验 + 模拟执行强制 `UNKNOWN`；记账仅在收敛为 `SUCCEEDED` 后发起
- [x] CHK013 与 Constitution §VII 一致：新增 6 项指标 + 关键动作 `FINANCIAL_AUDIT`（含 `traceId`/操作人/理由）
- [x] CHK014 与 `006-reconciliation` FR-007 口径一致：人工依据（`reason` / `operator`）MUST 非空
- [x] CHK015 与 Roadmap Phase 7「包含/不包含」一致：真实出款、税费、复杂分账、多币种清分均在 Out of Scope

## 可测试性

- [x] CHK016 SC-001~SC-010 均为可度量结果（净额一致率 / 幂等率 / 门禁拒绝率 / 零回写 / 重试上限）
- [x] CHK017 每个 US 均有 Independent Test 与 Acceptance Scenarios（Given/When/Then）
- [x] CHK018 每个 US 在 tasks.md 中有独立的测试任务与实现任务（T009~T038）

## 现状核实（防止写出「幻想式」需求）

- [x] CHK019 已核实结算核心链路已实现（资格校验 / 净额 / 持久化 / 八态状态机 / 双唯一约束 / 模拟执行 / 指标审计 / 5 个测试类），Spec 未按绿地项目描述
- [x] CHK020 已核实调整项恒为 0：`SettlementApplicationService.java:80` 硬编码 `0`，`Adjustment.java:6` 全项目零引用
- [x] CHK021 已核实事实无商户维度（N1）：`PlatformFact.java:13` / `ReconciliationSettlementFact.java:7-8` / `SettlementFact.java:6` 均无 `merchantId`，未假装能在本地校验归属
- [x] CHK022 已核实 reconciliation 404 未归一化（`FeignReconciliationClient.java:23-29`），记为 N2
- [x] CHK023 已核实 `close()` 应用层零调用、controller 无 close 端点（`SettlementBatch.java:98-104`、`SettlementController.java:25-41`），记为 N3
- [x] CHK024 已核实两个 Feign 客户端无超时配置（`MerchantFeignClient.java:10`、`ReconciliationFeignClient.java:10`、`application.yml:22-26`），记为 N4
- [x] CHK025 已核实幂等键跨商户/周期错配被静默返回（`SettlementApplicationService.java:49-52`），记为 N5
- [x] CHK026 已核实收敛无人工依据（`ResolveSettlementRequest.java:6` 仅 `status`），记为 N6
- [x] CHK027 已核实 settlement 侧无 ledger 网关（对比 `payment-service/.../FeignLedgerPostingGateway.java`），记为 G4
- [x] CHK028 已核实文档状态漂移三处（`technical-solution.md:101/106`、`settlement-service.md:91`），记为 G5

## 未决项（待 ADR 确认，实现阻塞）

- [ ] CHK029 ADR-0022（调整项模型：方向语义 / 持久化形态 / 登记门禁 / 净额公式 / 死代码处置）经负责人确认（Constitution §8.3/§8.4/§8.8）
- [ ] CHK030 ADR-0023（闸门纵深防御 / settlement→ledger 记账归属与时机）经负责人确认（Constitution §II.3/§8.4）
- [ ] CHK031 N1（事实无商户维度 ⇒ 跨商户串账）是否另立契约变更 Feature（为 `settlement-summary` 加 `merchantId`，§8.4），由负责人裁定
- [ ] CHK032 负净额是否应拒绝建批（当前保持允许 + 指标），由负责人裁定
- [ ] CHK033 建批后调整项的撤销/冲正流程归属（MVP 拒绝建批后登记），由负责人裁定
- [ ] CHK034 `technical-solution.md:101/106` 与 `settlement-service.md:91` 文档口径统一（FR-024 / T044）
- [ ] CHK035 `docs/adr/README.md` 索引止于 0005，未收录 0006/0007/0008，由负责人在文档收口时一并处置

## Notes

- 标记 `[x]` 仅表示需求质量已审，不代表实现完成。
- ADR 未确认前不得进入实现阶段（Constitution §VIII.6）。本 Feature 按负责人指示采用「最简实现 + 事后补 ADR 供决策」的推进方式，ADR-0022~0023 状态保持 **Proposed** 直至负责人确认。
