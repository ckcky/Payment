# Acceptance: 032-reconciliation-real-statement

> 回写文档（2026-09-21）。逐条对应 spec §13 必测清单、§14 验收标准与 tasks.md T24b；证据为合并 034 后 `mvnw -o clean verify -fae` 全绿与 demo 实测（容器模式演示栈，jar 由本分支构建）。

## 1. 必测清单（spec §13）

| 用例 | 测试 | 结果 |
|---|---|---|
| TC-032-1 正常全匹配 | `ReconciliationApplicationServiceTest.consistentBatchWhenNoDifferences` | ✅ |
| TC-032-2 金额不符精确到分 | `ReconciliationApplicationServiceTest.runReconciliationDetectsAllFourDifferenceTypes` | ✅ |
| TC-032-3 渠道长款不双计 | `runReconciliationProducesBatchWithMatchesAndChannelOnlyDifference` + §6.1 交叠（`ReconciliationMatchingTypedTest`） | ✅ |
| TC-032-4 平台短款 BLOCKER ⇒ 门禁 BLOCK | `ReconciliationSettlementRpcScenarioTest` + `ConfirmedFactGateTest`（settlement 侧商户校验） | ✅ |
| TC-032-5 STATUS_MISMATCH 只产差异不改 Payment | `runReconciliationDetectsAllFourDifferenceTypes` + `DifferenceResolveTest`；Payment 零改动由 `ReconciliationNoFactMutationTest` 守 | ✅ |
| TC-032-6 跨商户同号独立匹配不互抵 | `ReconciliationMatchingTypedTest.strongKeyMatchesWhenMerchantAndTypeAgree` / `merchantMismatchFallsBackToTypedKeyWithoutGuessingOwnership` | ✅ |
| TC-032-7 同账单重放幂等 | `StatementImportApplicationServiceTest.duplicateFingerprintReplayReturnsFirstImportWithoutNewLines` | ✅ |
| TC-032-8 更正账单并存不就地改 | `ReconciliationPeriodScenarioTest.twoPeriodsProduceDistinctBatchesWithOwnStatementSource` + 收口链路 `ReconciliationLifecycleTest.resolveByDiffNoAdvancesBatchAndAllowsClose` | ✅ |
| TC-032-9 跨期不重复结算 | `ReconciliationPeriodScenarioTest.periodFixturesYieldDifferentDifferenceSets` + confirmed-facts 期间过滤（payment `RefundFactsServiceTest` / settlement 镜像） | ✅ |
| TC-032-10 渠道到账入账平衡 | `ChannelFundPostingServiceTest.postsSettlementAndFeeEventsFromStatementRows`（CS-/CF- sourceId 确定性 + 零净额跳过 `zeroNetAndZeroFeeProduceNoEvents` / `replayIsDeterministicOnSourceIdForLedgerIdempotency`）；分录平衡/试算归 031 规则既有测试 | ✅ |
| TC-032-11 挂账后放行含事实只扣净影响 | `settlementSummaryExposesMatchedFactsForSettlement` + excludedFacts 净影响断言（`ReconciliationApplicationServiceTest`） | ✅ |
| TC-032-12 退款引用指向成功尝试 | payment `RefundFactsServiceTest`（双尝试事实收敛） | ✅ |
| TC-032-13 坏行不静默 | `ReconciliationMatchingTypedTest.v2RowWithoutMerchantIsUnknownMappingNotSilentlySkipped` + `CsvStatementParserOccurredAtTest`（归一缺陷留档） | ✅ |
| TC-032-14 关账拒写 | `ChannelFundPostingServiceTest.periodClosedErrorPropagatesUnchanged`（原样上抛）+ ledger `PeriodCloseTest`（031 门禁侧） | ✅ |
| 真库唯一键（`uk_import_identity`/`uk_diff_identity`） | 033 基座：`AuditAdjustmentPostingRealDbTest`（Testcontainers 真 MySQL）+ `schema-replay` 双路径门禁覆盖三新表 | ✅ |
| 词汇门禁（T18） | `AccountingVocabularyBoundaryTest` 032/T18 扩展：`CHANNEL_FEE`/`CHANNEL_SETTLEMENT` 字面量禁落 reconciliation/ledger/契约枚举之外 | ✅ |

## 2. 全量回归

- 合并 `origin/master`（含 034 C-19）后 `JAVA_HOME=… ./mvnw -o clean verify -fae`：**968 tests，0 失败 / 0 错误 / 0 跳过**（15 模块，EXIT=0）。
- **T24b（032 §依赖：034 记账同事务化后的回归重测）由本轮合并后全量回归 + demo 三场景实测覆盖**：`SettlementBatchReplayIdempotencyTest`（TT-11）适配 032/G4 商户锚契约后全绿（df51e3a）。
- `deployment/e2e-tests` 编译通过（b3b0086 适配新端点）。

## 3. Demo 实测（容器模式演示栈，jar 为本分支构建）

- `demo/reset.sh`：全量 schema 后**重放 032/034 守卫式增量迁移**（本轮修复，见 §4-1）→ 服务健康、种子回灌正常。
- `scenario-happy-path.sh`：**16 PASS**（支付主链 + 幂等吸收，回归无碍）。
- `scenario-audit.sh`：**39 PASS**。④b F7 跨账差异经 `POST .../differences/{id}/resolve` 人工收口直达 RESOLVED，**⑧ 关批 200——031 acceptance §3 登记的关批 409 已知问题由 032 处置策略化正式关闭**（031 遗留项闭环）。
- `scenario-reconciliation.sh`：**16 PASS**。导入（201 NORMALIZED，指纹幂等重放）→ run（无导入 400 `STATEMENT_UNAVAILABLE`，sample.csv 回退已退役）→ 按 RD 单号逐条 resolve → 差异台账分页查询 → 关批 CLOSED；渠道资金事实入账 `CS-`/`CF-{channel}-{period}` 在 ledger 侧逐笔可见。

## 4. Demo 实跑发现并修复（bb025ac + 78aa8ec，均补回归用例）

1. **reset.sh 不重放三位守卫式迁移**：TRUNCATE 不清表结构，存量卷 `audit_adjustments` 缺 `diff_no` → audit adjust 500。修复后 reset 显式重放 `032-*.sql`/`034-*.sql`（全新库为无害 no-op；旧 015~031 增量不适用全新库，白名单登记不放开）。CI schema 双路径门禁不覆盖此第三路径，属门禁盲区，已在本条留痕。
2. **`resolveDifferenceByNo` 批次推进失效**：台账行先于批次视图更新，「视图变化」判定恒假 → 末笔/重放收口后批次滞留 `HAS_DIFFERENCE`，关批 409。改为本单号收口即幂等推进 `PROCESSING`（CONSISTENT/CLOSED 不回拨），补 `ReconciliationLifecycleTest.resolveByDiffNoAdvancesBatchAndAllowsClose`。
3. **`occurredAt` 直插 DATETIME 列 500**：`CsvStatementParser` 补归一（`yyyy-MM-dd HH:mm:ss`/ISO/毫秒/尾 Z/纯日期均收；不可解析 ⇒ NULL 留档 raw_text，属归一性缺陷非结构性错误不整批拒），补 `CsvStatementParserOccurredAtTest` ×2；scenario 侧改传真实时间戳。
4. **scenario-audit ⑦ 用旧快照回炒已 RESOLVED 差异**（RESOLVED→ADJUSTED 回退致关批 409）：⑦ 前重拉差异列表。
5. **TC-032-14 无直测**：补 `periodClosedErrorPropagatesUnchanged`。

## 5. 验收标准（spec §14）

- AC-1 ✅ `sample.csv` 回退代码删除（3bca84d 死配置清理），run 无可用导入一律 400 显式失败；
- AC-2 ✅ `statement_imports`/`statement_lines`/`reconciliation_differences` 三表落地（守卫式迁移可重放），差异按状态/商户/周期分页；
- AC-3 ✅ `(merchantId, referenceType, reference)` typed 匹配 + `ConfirmedFactGate` 商户锚（未知归属拒绝结算，demo 合并回归即该契约的跨分支验证点）；
- AC-4 ✅ `CHANNEL_SETTLEMENT`/`CHANNEL_FEE` 唯一产生方 = 032 渠道资金事实链（双计三防线：确定性 sourceId、账单唯一来源 + ArchUnit 词汇门禁、FEE_MISMATCH 走 ADJUSTMENT）；
- AC-5 ✅ 结算口径 =「全部已确认事实 − excludedFacts 净影响」；G4 先于 G5 合入；
- AC-6 ✅ 处置策略化 `ADJUSTING`/`ADJUST_FAILED` 分态 + 失败台账（`postingFailureKeepsDifferenceVisibleAsAdjustFailedWithFailedLedgerRow`），`AutoDispositionPolicy` 默认双关；
- AC-7 ✅ demo audit/reconciliation 双场景升级并全绿（§3）。

## 6. 遗留（不阻塞本验收）

- 指标面板口径与 SLO 收口归 035（spec §12 明示「落地由 035 收口」；高基数纪律本 Feature 已守——`merchantId`/`diffNo`/`importNo` 未进任何指标标签）。
- `AutoDispositionPolicy` 生产启用属人类决策（阈值/白名单评审后开）。
- `differences_json` 停写不停读：读路径退役待一个观察期后评估。
