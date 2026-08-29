# Requirements Checklist: Refund 退款（缺口补齐）

**Purpose**: 需求质量校验 —— 确认 spec.md 的需求完整、无歧义、可测试，且与真实代码现状一致。
**Created**: 2026-08-29
**Feature**: [spec.md](../spec.md)

**Note**: 标记 `[x]` 表示 reviewer 已确认该需求质量项满足；`[ ]` 表示待确认/阻塞。

## 完整性

- [x] CHK001 每条 FR 都可映射到至少一个用户故事 / 验收场景（FR-001~FR-017 ↔ US1~US5）
- [x] CHK002 金额铁律在 FR-011 显式声明（禁 float/double，long 分或 BigDecimal），且不变量落在 data-model INV-1~INV-6
- [x] CHK003 幂等要求在 FR-006 / FR-007 显式声明，覆盖重复受理、重复收敛、重复记账三类
- [x] CHK004 失败不回滚业务事实在 FR-005 / FR-010 显式声明（Saga，禁 2PC/XA）
- [x] CHK005 三项已核实缺口（G1 部分退款不可达 / G2 fulfillment RPC 缺失 / G3 resolve 无防御断言）均有对应用户故事与任务（US1/US2/US3 + Phase 3~5）
- [x] CHK006 Spec 明确声明为「缺口补齐型」，未把已实现能力写成待建

## 一致性

- [x] CHK007 与 Constitution §II.3 一致：US4/FR-009 把退款接入已落地的 ledger-service（未另立账本）
- [x] CHK008 与 Constitution §III 边界 #5（Refund ≠ Payment Refund）一致：本 Feature 扩展编排面，不是只调渠道退款
- [x] CHK009 与 Constitution §III 边界 #6（Fulfillment 不强耦合）一致：撤销结果由 fulfillment 自身状态机决定，契约返回 `SKIPPED`/`REJECTED`
- [x] CHK010 与 ADR-0001 / Constitution §IV 一致：全部新增交互为同步 RPC + 幂等，未引入 MQ
- [x] CHK011 与 spec `004-ledger` 一致：复用其 `PostingRequest` 契约与科目语义，不重复定义账本模型
- [x] CHK012 契约与 data-model 一致：`refundedAmountMinor` 在响应/请求/记账三处口径统一

## 可测试性

- [x] CHK013 SC-001~SC-006 均为可度量结果（覆盖率 / 幂等率 / 不回滚率）
- [x] CHK014 每个 US 均有 Independent Test 与 Acceptance Scenarios（Given/When/Then）
- [x] CHK015 每个 US 在 tasks.md 中有独立的测试任务与实现任务（T008~T038）

## 现状核实（防止写出「幻想式」需求）

- [x] CHK016 已核实 refund-service 核心链路已实现（领域/状态机/持久化/幂等/RPC/测试），Spec 未按绿地项目描述
- [x] CHK017 已核实 `PARTIALLY_SUCCEEDED` 当前不可达（`RefundApplicationService.java:99-103` 只处理三态）
- [x] CHK018 已核实 refund→fulfillment 无任何代码（无 `FulfillmentGateway`；fulfillment 仅 `GET /{id}` 与 `on-payment-succeeded`）
- [x] CHK019 已核实 `resolve()` 直接驱动状态机、无前置断言（`RefundRpcCallbackService.java:24-36`）
- [x] CHK020 已核实 ledger-service 与 payment 侧记账网关已实现，退款侧无集成（004-ledger US2 缺口）

## 未决项（待 ADR 确认，实现阻塞）

- [ ] CHK021 ADR-0016（部分退款模型）经负责人确认（Constitution §8.3 新增关键资金字段 / §8.8 状态机变更）
- [ ] CHK022 ADR-0017（refund→fulfillment 编排）经负责人确认（§8.4 跨服务接口变更）
- [ ] CHK023 ADR-0018（refund→ledger 接入）经负责人确认（涉及 spec 004 与 005 的归属划分）
- [x] CHK024 ADR 编号冲突已解决（本包重编号为 `0006-refund-decisions.md` / ADR-0016~0018；既有 `0005-payment-reliability-impl-decisions.md` 的 ADR-0012~0015 不变），全局引用已同步
- [ ] CHK025 `technical-solution.md:101` 与 `roadmap.md` 的成熟度/状态标注过期，需按 ADR 结论统一修正

## Notes

- 标记 `[x]` 仅表示需求质量已审，不代表实现完成。
- ADR 未确认前不得进入实现阶段（Constitution §VIII.6）。
