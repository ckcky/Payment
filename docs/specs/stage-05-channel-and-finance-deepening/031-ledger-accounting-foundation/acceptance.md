# Acceptance: 031-ledger-accounting-foundation

> 回写文档（2026-09-21）。逐条对应 spec §10 必测清单与验收标准；证据为 `mvnw verify`（866 tests 全绿）与 demo 实测。

## 1. 必测清单（spec §10）

| # | 验收点 | 测试 | 结果 |
|---|---|---|---|
| 1 | 六类事件分录逐行正确（案例六余额终态） | `PaymentCapturePostingTest` / `RefundPostingTest` / `SettlementPostingTest` / `AdjustmentPostingTest` | ✅ |
| 2 | 重复事件回放首笔，分录与余额不重复累加 | `PostingIdempotencyTest.duplicateEventReplaysFirstPosting` | ✅ |
| 3 | 并发重复投递唯一赢家（真 MySQL 唯一键兜底） | `LedgerPostingConcurrencyTest` / `LedgerPostingRaceTest`（Testcontainers） | ✅ |
| 4 | 不平衡交易构造期拒绝，不落任何分录 | `PostingBalanceGateTest` | ✅ |
| 5 | 落库失败不落半套分录（原子性） | `LedgerPostingAtomicityTest` | ✅ |
| 6 | 调整/冲正（ADJUSTMENT + reverses 引用） | `AdjustmentPostingTest` | ✅ |
| 7 | 关账前试算平衡门禁 | `PeriodCloseTest` | ✅ |
| 8 | 按 (sourceType, sourceId) 溯源且不串源 | `SourceTraceabilityTest` | ✅ |
| 9 | 调用方不得再出现科目/借贷词表 | `AccountingVocabularyBoundaryTest`（ArchUnit） | ✅ |

## 2. 全量回归

- `JAVA_HOME=… ./mvnw -o verify -fae`：**866 tests，0 失败 / 0 错误 / 0 跳过**（2026-09-21，含 Testcontainers 真 MySQL 用例）。
- `deployment/e2e-tests` 编译通过（执行依赖演示栈，见 §3）。

## 3. Demo 实测（host 模式演示栈）

- `demo/reset.sh`：库重建 + **科目种子回灌**（031 reset 修复）→ 服务健康 9/9。
- `demo/scenario-happy-path.sh`：下单 → 选渠道建支付 → payUrl → 回调 SUCCEEDED → **重复回调幂等吸收** → 权益 AVAILABLE（支付链路 10 项 PASS；一轮因 `mvn verify` 并行抢 CPU 致 MQ 链路超时轮询窗，链路本身无误）。
- `demo/scenario-audit.sh`：30+ 步全 PASS，唯步骤⑧关批返回 409 —— **已知问题（非 031 回归）**：关账门禁（FR-018）要求差异达 VERIFIED/RESOLVED，而 F7 跨账差异（CROSS_LEDGER_MISMATCH）按设计 ADJUSTED 仍属未关态，且 TRANSFER 调整不会让 SETTLEMENT 来源的跨账核对转绿；017 期冒烟记录本就不含关批，算法与 031 前（`fbb0c16`）逐字节一致。处置策略化归 032。
- `demo/scenario-reconciliation.sh`：14 项全 PASS（含 `python3` 修正后的差异核对）。
- `ledger-service.log`：`/internal/ledger/accounting-events` 201 + FINANCIAL_AUDIT `ledger.posted`/`ledger.entries` 逐笔可见；入账日志 bizNo 补齐（`runWithBizNo(sourceId)`）后按 paymentNo 对链。

## 4. 遗留（不阻塞本验收）

- CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT / ADJUSTMENT 的**产生方**尚未切换（规则与解析已就绪）：032（对账）/ 034（可靠性）推进，spec 031 §12 与 design-summary 依赖图一致。
- schema 双路径重放 lint、L2b 公共基座扩面：033。
- `pending_postings` 调用方台账：034（spec 031 §12 设计，031 未建表）。
- 审计场景关批 409（F7 跨账差异无法经 TRANSFER 关闭）：差异处置策略化（含跨账差异的关闭路径）归 032。
