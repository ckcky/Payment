# Acceptance: Refund 退款（缺口补齐）

**Feature**: `005-refund` | **Date**: 2026-08-29 | **Spec**: [spec.md](spec.md)
**最终验收：2026-08-31** —— `mvn -o clean verify -fae` 全量 15 模块 **BUILD SUCCESS**

> ## 裁决摘要（2026-08-30 负责人裁决 / 2026-08-31 落地）
>
> | ADR | 裁决 | 影响 |
> |---|---|---|
> | ADR-0016 部分退款 | ❌ **Rejected（不做）** | **US1 整节不做**，已实现部分全部回退 |
> | ADR-0017 refund→fulfillment 编排 | ✅ **Accepted** | US2 全量落地 |
> | ADR-0018 refund→ledger 记账 | ✅ **Accepted** | US4 全量落地，记账金额 = `amountMinor` |
>
> 标记约定：`[x]` 通过 · `[-]` **不做（本次裁决）** · `[ ]` 未通过 / 遗留项。

## 功能验收

### US1 · 部分退款（缺口 G1）—— ⛔ 整节不做

> **ADR-0016 已裁决「部分退款不做」。** 本节条目全部**不适用**，保留仅作历史对照。
> 回退清单见 [ADR-0016 回退落地记录](../../adr/0006-refund-decisions.md)。

- [-] ~~渠道部分退回 → 落 `PARTIALLY_SUCCEEDED` 且记录 `refundedAmountMinor`~~
- [-] ~~渠道全额退回 → 落 `SUCCEEDED`，`refundedAmountMinor == amountMinor`~~ → **实际：全额退回落 `SUCCEEDED`** ✅
- [-] ~~渠道退回 0 / 超申请额 → 不落成功类状态，告警~~ → 渠道只回三态，无金额可比
- [x] 累计额度口径正确：终态**与在途**一律按**申请额**累计；超额申请落 `REJECTED` **且不发起渠道尝试**（SC-002 / H1）
      —— `RefundApplicationServiceTest#cumulativeCountsRequestedAmountForBothTerminalAndInTransit`
- [-] ~~数据不变量 INV-1~INV-6~~ → 现为 INV-1~INV-3（见 [data-model §3](data-model.md)）✅

### US2 · 后处理编排（缺口 G2）—— ✅ 通过

- [x] 确认退款同时触发 fulfillment 撤销与 entitlement 吊销（SC-004）—— `RefundScenarioTest#successfulRefundFiresAttemptAndPostProcessExactlyOnce`
- [x] 新增 `POST /internal/fulfillments/on-refund` 可用且幂等（contracts §2）—— `FulfillmentRefundController`
- [x] 后处理失败被记录为独立的 `RefundPostProcessAttempt`（含目标、原因、次数），可查询（SC-004）
- [x] 后处理失败**不回滚**退款成功（SC-004 / ADR-0017）—— `RefundScenarioTest#postProcessFailureDoesNotRollBackRefundSuccess`
- [x] 旧静默路径（`catch (RuntimeException ignored)`）已被 `RefundPostProcessOrchestrator` 的尝试记录 + 指标替代（FR-005）
- [x] `UNKNOWN` 退款不触发任何后处理（FR-007）—— `RefundApplicationServiceTest#unknownAttemptEndsUnknownWithoutPostProcess`
- [x] fulfillment 已交付时返回 `SKIPPED`/`REJECTED`，不被强制改写（Constitution 边界 #6）
- [ ] ⚠️ **遗留**：fulfillment 退款端点**缺专属测试**（tasks T017）

### US3 · 幂等与收敛边界（缺口 G3）—— ✅ 通过

- [x] 重复幂等键退款被吸收，不产生第二次渠道尝试（SC-003）—— `#duplicateRefundDoesNotTriggerSecondFundAction`
- [x] `UNKNOWN` 退款不被重复发起渠道退款尝试（SC-003）
- [-] ⚠️ `resolve` 对 `REQUESTED` **未抛** `STATE_TRANSITION_VIOLATION`；当前由状态机**静默吸收**（`transitionTo` 返回 `false`）。属与 spec 的已知偏差，已记于 tasks T027
- [x] `resolve` 对已终态幂等吸收；重复收敛只触发一次后处理与记账（SC-003）—— `#unknownRefundConvergesToSuccessAndPostProcessIsIdempotent`

### US4 · 记账接入（承接 004-ledger US2）—— ✅ 通过（含遗留）

- [x] 已确认退款在 ledger-service 留下 `sourceType=REFUND` 的平衡冲正 Posting（SC-005）—— `LedgerPostingGateway` + `FeignLedgerPostingGateway`
- [x] 记账金额 = **`amountMinor`**（ADR-0016 回退后成功退款恒为全额，无独立「实际退款金额」概念）
- [x] 幂等键 `REFUND:<refundIdempotencyKey>`，重复记账被幂等吸收
- [x] 记账失败/超时不回滚退款成功，落 LEDGER 目标的 `RefundPostProcessAttempt` 供对账/人工重放（SC-005 / ADR-0018）
- [ ] ⚠️ **遗留**：缺记账断言测试（tasks T030/T031）

### US5 · 可观测与对账事实 —— ✅ 通过（口径已按裁决收窄）

- [x] 退款指标：`refund.created/duplicate/rejected/succeeded/failed/unknown` + `refund.post_process_failed`（`RefundMetricsTest`）
- [-] ~~`refund.partially_succeeded`~~ —— 随 ADR-0016 **不做**
- [x] 每次资金状态迁移写入 `FINANCIAL_AUDIT`（含幂等键、金额、前后状态、traceId）（SC-006）
- [x] `confirmed-facts` **仅返回 `SUCCEEDED`**，金额取 `amountMinor`（SC-006）

## 非功能验收

- [x] 金额全程 `long` 最小货币单位，禁 `float`/`double`；不变量在受理/记账处校验（FR-011）
- [x] 状态迁移全部经 `Refund.transitionTo` 唯一入口，无散落 `setStatus`（FR-012）
- [x] 跨服务仅同步 RPC + 幂等，未引入 MQ / 2PC / XA（FR-013）
- [x] Database-per-service：refund-service 只读写 `refund` Schema（FR-014）
- [x] 全量测试通过；既有 refund 测试未被删改以迎合实现（FR-017、Constitution §VIII.3/4）
      —— 注：部分退款相关用例随裁决**删除**（`partialRefundReachesPartiallySucceededAndTracksConfirmedAmount` / `invalidRefundedAmountFallsToUnknown`），
      累计口径用例**改写**为新口径并在 Javadoc 中写明原因，均属裁决驱动的契约变更，非「改测试迎合错误实现」

## 决策验收（Constitution §8）

- [x] ADR-0016~0018 经负责人确认：ADR-0016 **Rejected**、ADR-0017 / ADR-0018 **Accepted**（2026-08-30）
- [-] ~~新增资金字段 `refunds.refunded_amount_minor`~~ → **裁决不做，列已删除（2026-08-31）**
- [x] 新增表 `refund_post_process_attempts` 经确认（§8.3）
- [x] 跨服务接口变更（新增 fulfillment 端点）经确认（§8.4）；~~`RefundAttemptResponse` 增字段~~ 已回退
- [-] ~~退款状态机变更（`PARTIALLY_SUCCEEDED` 可达）~~ → **裁决不做**，枚举保留但无调用方
- [x] ADR 编号冲突已处理（本包重编号为 `0006-refund-decisions.md` / ADR-0016~0018）

## 验收结论

- **状态**：✅ **已通过**（2026-08-31）
- **未通过 / 遗留项（已知、未闭环，不阻塞本 Feature 验收）**：
  1. T017 —— fulfillment 退款端点缺专属测试
  2. T030 / T031 —— 退款记账缺断言测试
  3. T027 偏差 —— `resolve` 对非 `UNKNOWN` 状态为静默吸收，非 spec 要求的显式拒绝
- **延后事项**：部分退款（ADR-0016）重新开放时，须一并解决：退款单拆分模型、多次退累计口径、权益/履约按比例回收、Ledger 部分冲正分录
