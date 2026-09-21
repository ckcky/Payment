# Plan: 032-reconciliation-real-statement（对账真实化）

**Feature**：032　**Spec**：[spec.md](spec.md)（v1.0，已裁决 🟢：27 项全部按推荐方案批准，见 [ADR-0080](../../../adr/0080-reconciliation-statement-and-fund-facts.md)）
**日期**：2026-09-21　**分支**：`feature/032-reconciliation-real-statement`

## 0. 实施范围与顺序（spec §15 四批，单 PR 内分批提交）

```
032-A 事实维度（必须先行，spec 决策 5）
  → 032-B 账单真实化（G1）
  → 032-C 渠道资金事实（G3）
  → 032-D 处置策略化（G5）
```

- **R4（032-D 自动处置）与 034 的关系**：自动处置的记账走**既有** `AuditLedgerGateway.postAdjustment`
  （ADJUSTMENT 事件，幂等键 `ADJUSTMENT:{adjustNo}` 由 ledger 派生），**不依赖** 034 C-19 的新代码即可开发；
  034 合入后 rebase 重测（「同事务优先」属于 ledger 侧行为升级，对调用方契约透明）。
  ⇒ 依赖 034 的任务在 [tasks.md](tasks.md) 标注「待 rebase 后验证」。
- **033 资产复用**：`deployment/test-infra`（RealMysqlTestSupport / SchemaBootstrap）用于唯一键真库断言；
  schema-lint + schema-replay 双路径门禁约束本 Feature 的 DDL（07/10 全量文件更新 + `032-*.sql` 增量守卫脚本）。

## 1. 关键设计落点（spec → 代码映射）

| Spec 条目 | 落点 |
|---|---|
| §7.1 导入批次 3 态 | `statement/StatementImport`（RECEIVED→NORMALIZED/REJECTED，不可逆） |
| §7.2 处置复用 5 态 + H-032-3 | `AuditDifferenceStatus` 增 `ADJUSTING`（瞬时）/`ADJUST_FAILED`（可见不计未收口）；RESOLVED=人工确认收口（新增 audit 侧 resolve 端点，F7 关闭路径） |
| §7.3 批次唯一约束 | `reconciliation_batches` uk(period)→uk(channel_code, period, import_id)；读路径改「最新批」语义 |
| §8.1 三级匹配 | `ReconciliationMatching` 新 typed 重载：①(merchantId,referenceType,reference) ②(referenceType,channelTxnNo) ③弱匹配只出候选（detail 标注，不改判） |
| §8.2 差异 8 类 | `DifferenceType` 增 `FEE_MISMATCH` / `DUPLICATE_CHANNEL` / `UNKNOWN_MAPPING`；`PLATFORM_ONLY` 不改名 |
| §8.3 三层幂等 | uk_import_identity / uk_line+uk_channel_txn / uk_diff_identity（spec §9 落库） |
| §8.4 结算口径（G4） | `settlementSummary`：facts=全部已确认事实；`excludedFacts[{reference,type,amountMinor,reason}]`=未收口差异净影响；settlement 净额=income−refund−Σwithheld+adjustments |
| §9 三新表 + 非破坏 ALTER | `07-reconciliation-schema.sql`/`10-audit-schema.sql`（全量最终形态）+ `032-reconciliation-statement.sql`（增量守卫迁移，存量库演进） |
| §10.1 入站端点 | statement-imports（POST/GET）、run、differences 分页查询、differences/{diffNo}/resolve、channels/{code}/fund-facts/{period}/post |
| §10.2 事件产生方 | `ChannelFundPostingService`：CHANNEL_SETTLEMENT(`CS-{channel}-{period}`) + CHANNEL_FEE(`CF-{channel}-{period}`)，sourceType=RECONCILIATION，复用 031 契约不改形状 |
| §10.2 双计防线 | ①ledger 派生幂等键（事件重放吸收）②ArchUnit 词汇门禁扩展（CHANNEL_FEE/CHANNEL_SETTLEMENT 词汇只许 reconciliation+ledger+契约）③FEE_MISMATCH 走 ADJUSTMENT 不补发 CHANNEL_FEE |
| §11 #1 fail fast | 删除 `sample.csv` 回退 + `reconciliation.statement_fallback` 指标；无导入 ⇒ 400 `STATEMENT_UNAVAILABLE`（+`reconciliation.statement_unavailable` 指标） |
| §12 指标 | statement_import{channel,result} / difference{kind,severity} / unmatched_amount_minor{currency} / autodisposition{policy,outcome} / ledger.channel_fund_posting{channel,eventType}；merchantId/diffNo 禁作标签 |

## 2. 实现决策（spec 未尽事项的收口，全部向 spec 语义对齐）

1. **period 口径**：spec §9 写 `period CHAR(7)`（YYYY-MM），但全系统现行事实（settlement_batches / audit_batches /
   reconciliation_batches / demo AUDIT_PERIOD=2026-08-31）均为「业务周期串」（日粒度日期串）。新表统一
   `VARCHAR(32)`（超集，兼容 YYYY-MM 与 YYYY-MM-DD），幂等键语义不变。**已登记为实现期偏差**（非 H 项裁决范围）。
2. **事实期间绑定**：payments/refunds 无 period 列，`confirmed-facts?period=` 以 `DATE(created_at)=period` 过滤
   （period 可解析为日期时）；period 缺省或非日期串（demo 周期串）⇒ 全量 + WARN（H-032-1 兼容窗口口径）。
   demo fixture（audit-faults.sql）同步把注入行 created_at 收进 2026-08-31，保证演示期间过滤后事实可见。
3. **结算「净影响」定义**（TC-032-11「挂账后放行仍含事实、只扣净影响」）：`excludedFacts[].amountMinor` =
   该未收口差异对结算净额的扣减额：PLATFORM_ONLY/STATUS_MISMATCH=事实全额（渠道未证实），AMOUNT_MISMATCH=|期望−实际|，
   FEE_MISMATCH/CHANNEL_ONLY/UNKNOWN_MAPPING=0（不影响商户结算口径）。facts 集合**仍含该事实**（不剔除）；
   settlement 侧 income/refund 按 excludedFacts 扣减后进 `batch.calculate`，批次 items 保留全量事实快照。
   未收口集合 = PENDING/SUSPENDED/ADJUSTING/ADJUSTED（spec §8.4 列举三种 + ADJUSTED：调账已完成但事实仍未证实，
   若放行会同结算双付——从资金正确性出发纳入未收口，与 FR-018 关批口径一致）。
4. **差异拆表迁移式口径**：`differences_json` 停写不停读；新批次差异只写 `reconciliation_differences`，
   批次聚合加载时由仓储从新表水合（JSON 空时）。旧 `Difference` 类型保留为聚合内视图，新 `ReconciliationDifference`
   为表权威模型（diffNo=RD 前缀，新增 BusinessNoType）。
5. **账单解析双格式**：v2 表头 `referenceType,reference,channelTxnNo,merchantId,amountMinor,feeMinor,currencyCode,status,occurredAt`
   + 旧 4 列表头（referenceKind=PLATFORM_NO，referenceType 空 ⇒ 匹配走 legacy reference 单键通道）；
   结构性错误（表头不识别/列数不足/金额非数字/币种非法）⇒ 整批 REJECTED；归一性缺陷（缺商户/缺键/类型不可识别）
   ⇒ 行保留 + `UNKNOWN_MAPPING` 差异（§11 #2/#3 分工）。
6. **CHANNEL_FEE 唯一来源 = FEE 类型账单行**（payment 行的 feeMinor 只参与 FEE_MISMATCH 判定，不进 CHANNEL_FEE
   聚合）——结构性杜绝 031 §18 登记的双计风险。
7. **自动处置策略**（H-032-6 保守起步）：代码枚举 `SMALL_CHANNEL_ONLY_SUSPEND` + 配置开关
   `reconciliation.autodisposition.enabled`（默认 false）+ `max-amount-minor`（默认 0=关闭）；仅 CHANNEL_ONLY、
   金额≤上限、PENDING、CNY。挂账经既有 SUSPEND 计划（ADJUSTMENT 事件）+ `audit_adjustments` 留痕
   （新增 `diff_no` 列回溯 recon diffNo）；失败 ⇒ ADJUST_FAILED（独立事务写失败台账，不回滚吞掉）。
8. **audit 侧人工收口（F7 关闭路径）**：新增 `POST /internal/audit/batches/{batchNo}/differences/{id}/resolve`
   （备注必填，沿用 Difference.resolve 纪律）——CROSS_LEDGER_MISMATCH 等调账后 recheck 无法转绿的差异，
   由人工确认收口至 RESOLVED 后关批（spec §7.2 「RESOLVED=人工确认收口」的落地）。
9. **RealAuditor 升级**：账单输入从 legacy 4 字段升级为导入的标准行（typed），静默跳过转可查询：
   行状态非 SUCCEEDED 且平台事实已确认 ⇒ 匹配层 STATUS_MISMATCH（可查询差异）；分录无对应事实 ⇒
   账证 ORPHAN_POSTING 既有覆盖（映射关系在测试中钉死）。
10. **无导入时 REAL/ALL 核对**：显式失败（NFR-008 数据源不可达批次失败、可安全重跑），不做静默空账单核对。

## 3. 测试策略（spec §13 TC-032-1~14 → 测试落点）

- 单元（纯函数/聚合）：匹配三级键、差异 8 类、导入幂等/REJECTED、结算净影响口径、自动处置策略门、
  AuditDifference 状态机（ADJUSTING/ADJUST_FAILED/RESOLVED）、ChannelFundFact 聚合与双计防线。
- Spring 集成（H2，Mockito fake 网关）：run 全链（含 STATEMENT_UNAVAILABLE fail fast）、差异拆表水合、
  resolve/diffNo、fund-fact posting 幂等重放、PERIOD_CLOSED 传播、TC-032-9 跨期。
- 真库（deployment/test-infra，Testcontainers）：`uk_import_identity` / `uk_diff_identity` 真实唯一键行为
  （TC-032-7/8 的 DB 语义断言）。
- ArchUnit：渠道事件词汇边界（双计防线②）。
- demo：scenario-audit.sh 关批可过（人工收口路径）+ scenario-reconciliation.sh 适配导入前置。

## 4. 不做（Non-Goals 复述）

真实出款、API 拉账单（H-032-5）、规则 DSL、多币种、对账推进 Payment/Refund 状态、直改 ledger_entries、
034 的失败台账/重试组件（032 只留幂等重放入口）、035 的告警规则。
