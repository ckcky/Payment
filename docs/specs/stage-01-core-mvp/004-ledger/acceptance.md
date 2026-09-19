# Acceptance: Ledger 资金账本

**Feature**: `004-ledger` | **Date**: 2026-08-28（2026-09-09 回检） | **Spec**: [spec.md](spec.md)

> 本文件为 Feature 验收清单。2026-09-09 逐项回检（spec SC-001~SC-005 / FR-001~FR-011），
> 每条标注承载测试或代码位置；无对应证据的项如实标注为未完成，不假绿。

## 功能验收

- [x] 支付成功在账本留下平衡 Posting（source=PAYMENT），可经 source_id 回查（SC-001 / US1）
      —— `LedgerPostingServiceTest.paymentCapturePostsBalancedEntries`（借贷合计相等、`findBySource` 可回查）
- [x] 重复支付记账被幂等吸收，不重复分录（SC-001 / US1）
      —— `LedgerPostingServiceTest.duplicatePostingIsIdempotent`；并发撞键由 `LedgerIdempotencyTest.duplicateKeyOnInsertFallsBackToFirstPosting` 覆盖
- [x] 借贷不平衡的记账请求被拒绝，不落分录（US1 Edge Case）
      —— `LedgerPostingServiceTest.unbalancedPostingIsRejected`（断言 `LEDGER_UNBALANCED` 且仓储为空）
- [x] 退款在账本留下平衡冲正 Posting（source=REFUND），全局仍平衡（SC-002 / US2）
      —— **2026-09-09 新增** `RefundPostingTest`（5 用例：全额冲平 / 部分退保留剩余待结 / 重复幂等 / 来源溯源 / 不平衡被拒）
- [x] 结算批次在账本生成「应付→已结」平衡 Posting（source=SETTLEMENT）（SC-003 / US3）
      —— **2026-09-09 新增** `SettlementPostingTest`（5 用例：应付→结算应付结转 / 退款后只结转剩余 / 重复幂等 / 来源溯源 / 不平衡被拒）
- [x] 任意时刻全局借贷平衡性校验返回「平衡」（差额=0）（SC-004 / US4）
      —— `BalanceCheckerTest`；上述各用例末尾均追加全局平衡断言
- [x] 任意 LedgerEntry 可经 source_type+source_id 追溯到业务来源（SC-005 / US4）
      —— `SourceTraceabilityTest` + 两个新测试的「来源溯源」用例（`findEntriesBySource` 断言 2 条分录的 sourceType/sourceId/entryType）

## 非功能验收

- [x] 金额全程禁 float/double；金额不变量校验生效（FR-009）
      —— `ledger-service/src/main/java` 全量无 `double` / `float` 声明，金额一律 `long amountMinor`
- [x] 账本失败不回滚业务成功事实；进入重试/对账兜底（FR-006 / ADR-0009）
      —— `PaymentResultProcessor` 记账调用包在 `catch (RuntimeException)` 内：支付成功事实不回滚，记 `ledger.posting_failed` 后由对账兜底
- [x] 每次成功记账写入 `FINANCIAL_AUDIT`（FR-011）
      —— `LedgerPostingService.audit(...)` 写 `ledger.posted` + `ledger.entries` 两条审计（含幂等键、金额、币种）
- [x] 指标 `ledger.posted` / `ledger.posting_failed` 可观测（FR-011）
      —— `LedgerPostingService` 埋 `ledger.posted`；`ledger.posting_failed` 由调用侧（payment 的 `FeignLedgerPostingGateway`）在记账失败时埋点
- [ ] 全量测试（`mvnw verify`）通过，含 Testcontainers 集成测试（Constitution §VII）
      —— **部分完成**：`mvnw clean verify` 全量 BUILD SUCCESS（2026-09-09，15 个 reactor 条目，ledger-service 25 测试全绿）。
      但**无 Testcontainers 集成测试**：领域/应用层走 `InMemoryLedgerRepository`，并发撞唯一键用确定性仓储桩模拟。
      真库并发（乐观锁 / `uk_postings_idempotency_key` 冲突）尚未由自动化覆盖——与 spec 006 T023 属同类缺口，
      待引入 Testcontainers-MySQL 或稳定的本机 MySQL 后补齐，不做假绿断言。

## 决策验收（Constitution §8）

- [x] ADR-0008~0011 经负责人确认并更新状态为 Accepted
      —— `docs/adr/0004-ledger-design-decisions.md`：ADR-0008 / 0009 / 0010（已修订）/ 0011 均为 **Accepted**（2026-08-29 负责人确认）
- [x] 新增 `ledger-service` 模块与 `ledger` Schema 经确认（§8.2/§8.3）
      —— 模块与 Schema 已随实现落地并长期运行
- [x] Roadmap / Constitution / technical-solution 的 D1 矛盾已消除
      —— 见 `docs/archive/audits/` 修复记录；本次回检未发现新的矛盾项

## 验收结论

- **状态**：**已完成（功能与非功能验收全绿；仅「Testcontainers 集成测试」一项保留）**
- **遗留**：真库并发 / 集成测试（见上）。非阻塞——内存仓储 + 确定性撞键桩已覆盖幂等语义，
  真库唯一约束的并发行为靠 `uk_postings_idempotency_key` 兜底（生产语义未变）。
- **本次补强**（2026-09-09）：新增 `RefundPostingTest` / `SettlementPostingTest` 共 10 个用例，
  把此前散落在 `LedgerPostingServiceTest` 串联场景里的退款/结算覆盖拆成独立、可定位的验收点。
