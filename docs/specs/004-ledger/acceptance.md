# Acceptance: Ledger 资金账本

**Feature**: `004-ledger` | **Date**: 2026-08-28 | **Spec**: [spec.md](spec.md)

> 本文件为 Feature 验收清单。实现阶段完成后逐项勾选。当前（2026-08-28）处于**文档先行**阶段，全部未勾选。

## 功能验收

- [ ] 支付成功在账本留下平衡 Posting（source=PAYMENT），可经 source_id 回查（SC-001 / US1）
- [ ] 重复支付记账被幂等吸收，不重复分录（SC-001 / US1）
- [ ] 借贷不平衡的记账请求被拒绝，不落分录（US1 Edge Case）
- [ ] 退款在账本留下平衡冲正 Posting（source=REFUND），全局仍平衡（SC-002 / US2）
- [ ] 结算批次在账本生成「应付→已结」平衡 Posting（source=SETTLEMENT）（SC-003 / US3）
- [ ] 任意时刻全局借贷平衡性校验返回「平衡」（差额=0）（SC-004 / US4）
- [ ] 任意 LedgerEntry 可经 source_type+source_id 追溯到业务来源（SC-005 / US4）

## 非功能验收

- [ ] 金额全程禁 float/double；金额不变量校验生效（FR-009）
- [ ] 账本失败不回滚业务成功事实；进入重试/对账兜底（FR-006 / ADR-0009）
- [ ] 每次成功记账写入 `FINANCIAL_AUDIT`（FR-011）
- [ ] 指标 `ledger.posted` / `ledger.posting_failed` 可观测（FR-011）
- [ ] 全量测试（`mvnw verify`）通过，含 Testcontainers 集成测试（Constitution §VII）

## 决策验收（Constitution §8）

- [ ] ADR-0008~0011 经负责人确认并更新状态为 Accepted
- [ ] 新增 `ledger-service` 模块与 `ledger` Schema 经确认（§8.2/§8.3）
- [ ] Roadmap / Constitution / technical-solution 的 D1 矛盾已消除（见 ../../archive/audits/ 修复记录）

## 验收结论

- **状态**：未完成（待 ADR 确认 + 实现）
- **阻塞**：ADR-0008~0011 待负责人决策
