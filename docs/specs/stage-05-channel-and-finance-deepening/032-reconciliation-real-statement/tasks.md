# Tasks: 032-reconciliation-real-statement

> 完成即 ✅。批序遵循 spec §15（A→B→C→D）；「待 rebase 后验证」= 依赖 034 C-19 合入后由编排者安排 rebase 重测。

## 032-A 事实维度（G4 必须先行）

- [x] T1 payment-service：`confirmed-facts` 加可选 `period` 入参（日期串 ⇒ `DATE(created_at)` 过滤；缺省全量兼容）；`RefundFactResponse` 补 `merchantId`（经 payment 反查）
- [x] T2 payment-service C-22：退款事实渠道引用精确化——只取 SUCCEEDED 退款渠道尝试，确定性排序（id 降序），不再取首条
- [x] T3 reconciliation：`PaymentFactDto`/`RefundFactDto`/`PlatformFact`/`CertificateFact`/`SettlementFact` 全链补 `merchantId`；Feign 客户端透传 `period`
- [x] T4 reconciliation：`LedgerPostingView` 补 `period`/`postedAt`/`status`（031 出参已有，映射不再丢弃）
- [x] T5 G4 结算口径：`settlementSummary` 改「全部已确认事实 − 未收口差异净影响」，响应增 `excludedFacts`；settlement 侧 `ReconciliationSummary`/`ConfirmedFactGate`（商户校验）适配，净额扣减 excluded 汇总
- [x] T6 测试：跨期事实过滤（TC-032-9）、结算口径（TC-032-11）、退款引用精确（TC-032-12）、ConfirmedFactGate 商户校验

## 032-B 账单真实化（G1）

- [x] T7 Schema：`07-reconciliation-schema.sql`/`10-audit-schema.sql` 增三新表（statement_imports/statement_lines/reconciliation_differences）+ 批次表增列改唯一键 + audit 两表增列（全量最终形态）；`032-reconciliation-statement.sql` 守卫式增量迁移（存量库演进，过 schema-lint L-3）
- [x] T8 `statement/StatementImport` 聚合 + `StatementLine` 实体 + `BusinessNoType.STATEMENT_IMPORT("SI")`/`RECONCILIATION_DIFFERENCE("RD")`
- [x] T9 `StatementParser` 端口 + CSV 实现（v2/legacy 双表头；结构性错误整批 REJECTED；归一缺陷行留档）
- [x] T10 `StatementImportApplicationService`：导入（SHA-256 指纹三层幂等之导入层）+ 台账查询 + `reconciliation.statement_import{channel,result}` 指标 + StructuredAuditLogger `statement_import`
- [x] T11 匹配升级：`ReconciliationMatching` typed 重载（三级键、FEE_MISMATCH/DUPLICATE_CHANNEL/UNKNOWN_MAPPING、弱匹配只出候选）；legacy 4 列账单走原 reference 单键通道
- [x] T12 `ReconciliationDifference` 领域 + 仓储（Mybatis + InMemory）；批次差异拆表水合（differences_json 停写不停读）；批次增 channelCode/importId、uk(channel_code, period, import_id) 幂等回查
- [x] T13 run 编排：`POST /internal/reconciliation/run {period, channelCode?, importNo?}`；无可用导入 ⇒ 400 STATEMENT_UNAVAILABLE + 指标；删除 sample.csv 回退与 `statement_fallback` 指标（含旧测试按新契约改写）
- [x] T14 端点：statement-imports（POST/GET）、differences 分页查询（period/status/merchantId/kind）、`differences/{diffNo}/resolve`（备注必填）
- [x] T15 测试：TC-032-1/2/5/6/7/8/13（单元+集成）；真库唯一键断言（test-infra：uk_import_identity/uk_diff_identity）

## 032-C 渠道资金事实（G3）

- [x] T16 `ChannelFundFact` 聚合器：netReceived=ΣPAYMENT−ΣREFUND(+ΣSETTLEMENT)；channelFee=ΣFEE 行（唯一合法来源，防双计）
- [x] T17 `ChannelFundPostingService` + 端点 `POST /channels/{channelCode}/fund-facts/{period}/post`：CHANNEL_SETTLEMENT/CHANNEL_FEE 事件（sourceType=RECONCILIATION，sourceId=CS-/CF- 派生）；净额与费用为 0 跳过；幂等重放
- [x] T18 双计防线：ledger 派生幂等键重放断言（TC-032-10）；FEE_MISMATCH ⇒ ADJUSTMENT 不补发 CHANNEL_FEE 断言；ArchUnit 词汇门禁扩展（CHANNEL_FEE/CHANNEL_SETTLEMENT 词汇仅 reconciliation/ledger/契约可引用）
- [x] T19 RealAuditor 账实核对接入真实账单行（typed）；静默跳过转可查询差异（状态非 SUCCEEDED ⇒ 匹配层 STATUS_MISMATCH；无对应事实分录 ⇒ ORPHAN_POSTING 既有覆盖）；PERIOD_CLOSED 传播（TC-032-14）
- [x] T20 测试：TC-032-3/10/13/14

## 032-D 处置策略化（G5 / R4）

- [x] T21 `AuditDifferenceStatus` 增 `ADJUSTING`（瞬时，未收口）/`ADJUST_FAILED`（可见、不计未收口）；`AuditDifference.resolve(note,actor,at)`（备注必填、幂等刷新）
- [x] T22 audit 侧人工收口端点 `POST /internal/audit/batches/{batchNo}/differences/{id}/resolve`（F7 关闭路径）；处置 RPC 失败 ⇒ 独立事务写 FAILED 台账 + 差异 ADJUST_FAILED，不静默吞
- [x] T23 `AutoDispositionPolicy`（枚举 SMALL_CHANNEL_ONLY_SUSPEND + enabled/max-amount-minor 配置，默认双关）；run 后自动挂账：ADJUSTMENT 事件 + `audit_adjustments` 留痕（diff_no 回溯）+ recon 差异置 SUSPENDED/dispositionRef；指标 `reconciliation.autodisposition{policy,outcome}` + 日志 `difference_autodispose`
- [x] T24 测试：策略门（超限/关闭/非 CHANNEL_ONLY 不动）、成功留痕、失败 ADJUST_FAILED 可见（TC 对应 AC-6）
- [x] T24b 依赖 034 C-19（ledger 记账同事务优先）的回归重测：自动处置与 audit suspend/adjust 在 034 合入后的记账行为 —— **已验证（2026-09-21 合并 master(034) 后全量回归 + demo audit/reconciliation 场景实测，见 acceptance §2/§3）**

## 验收与收口

- [x] T25 demo：scenario-audit.sh 增账单导入 + 人工收口步骤（⑧ 关批可过，实跑验证）；scenario-reconciliation.sh 增导入前置；audit-faults.sql 注入行补 merchant_id/期间内 created_at；2026-08-31.csv/2026-09-30.csv 升级 v2 格式
- [x] T26 全量 `./mvnw -o clean verify -fae` 全绿（JDK26）＋ schema-lint / schema-replay 双路径通过
- [x] T27 L0 文档同步：systems/reconciliation-service.md、systems/settlement-service.md、technical-solution.md 对账节（最小面）
- [x] T28 docs：CHANGELOG 顶部、specs/README 032→🟢、roadmap、ADR-0080 flip 🟢 Accepted、ADR README 索引、acceptance.md 回填

## 依赖与风险

| 项 | 说明 |
|---|---|
| 034 C-19 | 仅 T24b 回归重测依赖；开发不阻塞 |
| spec §9 period CHAR(7) | 与全系统周期串口径冲突，按 plan.md §2.1 以 VARCHAR(32) 落地（偏差已登记） |
| demo 期间过滤 | audit-faults.sql 的 created_at 收进 AUDIT_PERIOD，否则 confirmed-facts(period) 过滤后演示事实为空 |
