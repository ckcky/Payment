# Tasks: 031-ledger-accounting-foundation

> 回写文档（2026-09-21）：批次随实现实况补记，全部 ✅ 的验收口径见 [acceptance.md](acceptance.md)。

## 批次 1：契约与错误码 ✅

- [x] T1 `common-dto`：`AccountingEventRequest` / `AccountingEventResponse` / `AccountingEventType` / `AccountingSourceType` / `AccountCode` / `LedgerDirection`
- [x] T2 `common-core`：`ErrorCodes.LEDGER_*`（不平衡 / 科目未登记 / 期间已关账等）
- [x] T3 `RpcContractTest` 快照更新；`PostingRequest` / `PostingResponse` 退役

## 批次 2：两级科目 ✅

- [x] T4 `account_definitions` / `accounts` 表 + 8 科目 seed（`09-ledger-schema.sql`，幂等）
- [x] T5 `AccountDefinition` / `AccountInstance` 领域模型 + `AccountRepository`
- [x] T6 `AccountResolver`（definition+owner → 实例，未登记即拒绝）+ `AccountDefinitionValidator`

## 批次 3：PostingRule ×6 + Registry ✅

- [x] T7 `PostingRule` 端口 + `PostingLine`（科目码 + owner + 方向 + 金额）
- [x] T8 六规则：PAYMENT_CAPTURE / REFUND / CHANNEL_FEE / CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT / ADJUSTMENT（含负净额反向成行）
- [x] T9 `PostingRuleRegistry` 启动 selfCheck（事件类型 ↔ 规则一一对应、展开非空）

## 批次 4：PostingEngine + 幂等 ✅

- [x] T10 派生幂等键 `{eventType}:{sourceId}`；回查回放
- [x] T11 `PostingEngine` 管线：规则 → 解析 → `Posting` 聚合（平衡门禁）→ 落库
- [x] T12 `DuplicateKeyException` → 回查回放（并发唯一赢家）

## 批次 5：余额投影与账期 ✅

- [x] T13 `account_balances` 同事务累加 + `BalanceChecker`（signed / trial / balances）
- [x] T14 `POST /balances/rebuild` 全量重建
- [x] T15 `ledger_periods` + `PeriodService` 关账试算门禁

## 批次 6：四调用方切换 ✅

- [x] T16 payment：capture / refund / channelFee 投递事件，删除组分录与科目常量
- [x] T17 settlement：MERCHANT_SETTLEMENT 事件化
- [x] T18 reconciliation·audit：ADJUSTMENT 事件化；ledger 侧审计查询随新契约
- [x] T19 `payments.merchant_id` 列（`03-payment-schema.sql` + 实体/仓储）

## 批次 7：测试与门禁 ✅

- [x] T20 必测 9 项（spec §10）：分录逐行 / 余额终态 / 重复回放 / 并发唯一赢家 / 不平衡拒绝 / 半套不落库 / 调整冲正 / 关账门禁 / 溯源不串源
- [x] T21 `AccountingVocabularyBoundaryTest`（ArchUnit：调用方词表禁用）
- [x] T22 Testcontainers 真 MySQL：`LedgerPipelineIntegrationTest` / `LedgerPostingAtomicityTest` / `LedgerPostingConcurrencyTest`

## 批次 8：schema / demo / e2e / 文档 ✅

- [x] T23 `031-ledger-accounting-foundation.sql` 增量迁移；`demo/reset.sh` 回灌科目种子
- [x] T24 e2e 快照 / 对账 / 结算门 / 幂等回调 / Invariants 随新契约更新；demo fixtures 同步
- [x] T25 L0 同步：`systems/ledger-service.md` 重写 + `technical-solution.md` 三处修订 + roadmap / CHANGELOG
- [x] T26 ADR-0077~0079 转 Accepted 并同步索引
