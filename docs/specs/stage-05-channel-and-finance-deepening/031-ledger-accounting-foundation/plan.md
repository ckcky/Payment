# Plan: 031-ledger-accounting-foundation（实现计划）

**配套**：[spec.md](spec.md) · [tasks.md](tasks.md) · [acceptance.md](acceptance.md)
**决策**：[ADR-0077~0079](../../../adr/0077-ledger-accounting-foundation-decisions.md)（🟢 **Accepted**，2026-09-21 负责人裁决 D-1~D-7 按 spec 推荐方案批准）
**状态说明**：本计划为**回写文档**（2026-09-21）——031 按「契约先行、代码随后」实施，实现完成度已到验收门（见 acceptance.md），Plan/Tasks/Acceptance 三件按实况补记，供评审与追溯。

---

## 0. 前置门状态

| 门 | 内容 | 状态 |
|---|---|---|
| 门 0 | 031 Spec + ADR-0077~0079 入 master | ✅（PR #10 前置提交 95905d3） |
| 门 1 | D-1~D-7 负责人裁决 | ✅ 2026-09-21 按 spec 推荐方案批准 |
| 门 2 | 载体依赖（033 L2b） | ⚠️ 未放宽即先行：真库并发测试在 031 内以**最小 Testcontainers 测试作用域**落地（`LedgerPostingConcurrencyTest` 等），033 随后扩面成公共基座 |

## 1. 技术路径（实际执行）

```
AccountingEvent(common-dto) → PostingEngine ─┬→ PostingRuleRegistry（规则×6 + selfCheck）
                                             ├→ AccountResolver（两级科目解析）
                                             ├→ Posting 聚合（平衡门禁 + 派生幂等键）
                                             └→ 同事务余额投影（account_balances）+ 账期（ledger_periods）
```

1. **契约与错误码**（批 1）：`AccountingEventRequest/Response/EventType/SourceType/AccountCode/LedgerDirection` + `ErrorCodes.LEDGER_*`；退役 `PostingRequest/PostingResponse`。
2. **两级科目**（批 2）：`account_definitions` / `accounts`（就地演进，id=3 更名 FEE_REVENUE）；`AccountResolver` 未登记 owner 即拒绝；`AccountDefinitionValidator`。
3. **PostingRule ×6 + Registry**（批 3）：PAYMENT_CAPTURE / REFUND / CHANNEL_FEE / CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT / ADJUSTMENT；启动期 selfCheck。
4. **PostingEngine + 幂等**（批 4）：派生幂等键 `{eventType}:{sourceId}`；回查→规则→解析→聚合→落库；`DuplicateKeyException` 回放；不平衡构造期拒绝。
5. **余额投影与账期**（批 5）：`account_balances` 同事务累加 + `/balances/rebuild` 重建；`ledger_periods` + 关账试算门禁。
6. **四调用方切换**（批 6）：payment / refund / settlement / reconciliation-audit 改投事件；删除调用方组分录与科目常量；`payments.merchant_id`。
7. **测试与门禁**（批 7）：必测 9 项 + ArchUnit 词表边界 + Testcontainers 真库并发/原子性。
8. **schema 与 demo**（批 8）：`09` 重写 + `031-*.sql` 增量 + `reset.sh` 回灌科目种子 + e2e 快照更新。

## 2. 关键取舍

- **聚合命名**：沿用 `Posting`（spec 文中 LedgerTransaction 的实现名），避免全仓无谓改名；语义一致（一次事件 = 一组平衡分录）。
- **幂等键账本派生**（D4）：上游不再造键，`{eventType}:{sourceId}` 天然吸收重复投递。
- **Testcontainers 先行最小化**：只落在 ledger 并发/原子性测试（测试作用域依赖），公共基座与 lint 归 033——与 ADR-0081 不冲突。
- **bizNo 补齐**：入账 HTTP 入口 `TraceContext.runWithBizNo(sourceId)`（新增 Supplier 重载），入账日志可按业务单号对链。

## 3. 风险与回滚

- 科目 seed 幂等（`INSERT ... ON DUPLICATE`），重放安全；`031-*.sql` 与 `09` 双路径均可重放（033 lint 收口）。
- 调用方切换一刀切：`PostingRequest/PostingResponse` 同批删除，无双轨期（D-1 推荐方案）。
