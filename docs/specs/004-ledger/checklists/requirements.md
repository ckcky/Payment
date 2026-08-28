# Requirements Checklist: Ledger 资金账本

**Purpose**: 需求质量校验 —— 确认 spec.md 的需求完整、无歧义、可测试。
**Created**: 2026-08-28
**Feature**: [spec.md](../spec.md)

**Note**: 本清单由 `/speckit-checklist` 生成思路复用；标记 `[x]` 表示 reviewer 已确认该需求质量项满足。

## 完整性

- [x] CHK001 每条 FR 都可映射到至少一个用户故事 / 验收场景（FR-001~FR-011 ↔ US1~US4）
- [x] CHK002 金额铁律在 FR-009 显式声明（禁 float/double，long 分或 BigDecimal）
- [x] CHK003 幂等要求在 FR-004 显式声明，且覆盖重复/乱序（Edge Cases）
- [x] CHK004 失败不回滚业务事实在 FR-006 / ADR-0009 显式声明（Saga，禁 2PC）

## 一致性

- [x] CHK005 spec 与 Constitution §II.3（复式记账 MUST）一致，且解决 Roadmap 延后矛盾（D1）
- [x] CHK006 跨服务同步 RPC + 幂等 与 ADR-0001 / §IV 一致，未引入 MQ
- [x] CHK007 科目表 / 业务映射与 data-model.md §2/§5 一致

## 可测试性

- [x] CHK008 SC-001~SC-005 均为可度量结果（覆盖率/平衡性/幂等率）
- [x] CHK009 每个 US 均有 Independent Test 与 Acceptance Scenarios（Given/When/Then）

## 未决项（待 ADR 确认）

- [ ] CHK010 ADR-0008~0011 状态由负责人确认（Constitution §8）→ 实现前阻塞
- [ ] CHK011 科目表最终编码（ADR-0008）确认后回填 data-model §2
- [ ] CHK012 金额表示（Money VO vs long 分，ADR-0010）确认后回填 FR-009

## Notes

- 标记 `[x]` 仅表示需求质量已审，不代表实现完成。
- ADR 未确认前不得进入实现阶段（Constitution §VIII.6）。
