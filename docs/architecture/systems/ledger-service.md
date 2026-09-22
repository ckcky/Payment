# ledger-service 系统设计

**服务**：ledger-service（复式记账 / 资金单一事实源）
**端口**：8090 | **Schema**：`ledger`（`deployment/schema/09-ledger-schema.sql` + `031-ledger-accounting-foundation.sql`）| **包根**：`com.payment.ledger`

> 标注约定：无标记 = 已实现；`[目标]` = 建议值待确认；`[待定]` = 留待后续。
> Feature 031（2026-09-21）重构：入站契约事件化（ADR-0077）、两级科目（ADR-0078）、余额投影与期间（ADR-0079），**记账决策权从调用方收归账本**——调用方只报已确认财务事实，科目与借贷方向由账本决定。

---

> **章节结构**：遵循 [system-design-standard](../../standards/system-design-standard.md) 的 15 章骨架。
> 2026-09-22 文档治理将原 9 章结构重排为标准章号，**正文内容未删改**。

---

## 1. 职责（Responsibility）

复式记账（借贷平衡）、科目目录与记账规则（Posting Rule）、幂等吸收重复入账、不可变 append-only 分录、余额投影 / 试算平衡 / 账期、按业务来源追溯、资金审计日志

### 1.1 技术指标

| 指标 | 现状 |
|---|---|
| 记账吞吐 / 延迟 | 随系统压测基线（见 `docs/adr/0058-performance-baseline.md`） |
| 多币种 | 当前仅 CNY；`currency` 维度已建模，按币种隔离借贷 |

## 2. 不负责（Non-Responsibility）

支付/退款/结算的业务决策与费率计算（费率由上游算好随事件携带）；实际出款（SETTLEMENT_PAYABLE 仅记负债）；业务侧科目申请流程（科目变更走 migration + seed）

## 3. 上下文与约束（Context）

### 3.1 上下游依赖方向

- **上游依赖**：payment-service（PAYMENT_CAPTURE / REFUND / CHANNEL_FEE 事件）、settlement-service（CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT 事件）、reconciliation-service（ADJUSTMENT 事件；另读账本事实做对账）
- **下游依赖**：无（自身持有 `ledger` 库，不反向依赖任何业务领域）
- **依赖方式**：跨服务交互一律经公开 REST / Feign RPC 或事件通道，**禁止**直接 SQL 他服务 Schema（Database-per-Service，见 [technical-solution.md §3.1](../technical-solution.md)）。

### 3.2 硬约束（Constitution / ADR）

- **资金单一事实源**：所有资金变动 MUST 经账本（Constitution §II / ADR-0008，部分被 0077/0078 取代），业务服务不得自记资金。
- **记账决策权归账本**（ADR-0077）：上游只投递 `AccountingEvent`（事件类型 + 业务来源 + 金额口径 + 商户/渠道维度），**禁止调用方组分录 / 传 accountId / 硬编码科目**（`AccountingVocabularyBoundaryTest` 门禁）。
- **借贷平衡门禁**：`Posting` 聚合根构造期即校验同币种 `sum(DEBIT) == sum(CREDIT)`；不平衡直接抛 `LEDGER_UNBALANCED`，**不落任何分录**。
- **分录不可变**：`LedgerEntry` 一经提交 MUST NOT UPDATE/DELETE；更正只能新增 ADJUSTMENT 事件（冲正/调账）。
- **幂等键由账本派生**（ADR-0077 D4）：`{eventType}:{sourceId}`，`uk_postings_idempotency_key` 唯一约束兜底，重复事件回放首次结果——上游不需要自己造幂等键。
- **禁 2PC/XA**：记账 RPC 失败不回滚上游业务成功事实；调用方侧补偿（`pending_postings` 台账）归 Feature 034（ADR-0079 §12 设计，031 未建表）。
- **仅内部调用**：端点挂在 `/internal/ledger`，仅被其他服务经 Feign 调用，不面向公网。
- **金额铁律**：金额一律最小货币单位 `long`（`*Minor`）；净额可负（负数由规则反向成行，`requireNetSigned`）。

### 3.3 与对账 / 运维的关系

- **对账依据（FR-007/SC-004）**：`GET /balance` 全局借贷差额 + `GET /trial-balance` 期间试算，reconciliation-service 校验「账务事实」自洽。
- **来源追溯（FR-008）**：`GET /entries` 按 `sourceType + sourceId` 回查某笔业务事实的全部分录（交易级，分录归属交易）。
- **余额对表**：`GET /balances` 是分录投影的读模型，`POST /balances/rebuild` 可全量重建自愈（对账发现投影漂移时的修复入口）。
- **资金审计（FR-011）**：每笔成功记账经 `StructuredAuditLogger` 落审计。

## 4. 领域模型（Domain Model）

### 4.1 入站契约 `AccountingEvent`（上游唯一语言）

16 字段值对象（`common-dto` 的 `AccountingEventRequest` 与之一一对应）：
`eventType`、`sourceType`(PAYMENT/REFUND/SETTLEMENT/RECONCILIATION)、`sourceId`（业务单号）、`currency`、金额口径（`grossAmount` / `merchantFee` / `channelFee` / `netAmount` / `amount`，按事件类型取用）、维度（`merchantId` / `channelCode`）、冲正引用（`reversesEventType` / `reversesSourceId`，ADJUSTMENT 用）。

### 4.2 聚合根 `Posting`（一次业务事件 = 一组平衡分录）

- 字段：`postingNo`（LP+雪花）、`eventType`、`idempotencyKey`（派生）、`sourceType`、`sourceId`、`currency`、`period`（入账期间 YYYY-MM）、`entries`、`status`(POSTED)。
- 不变量：至少 2 条分录；同币种借贷平衡；构造期 `requireBalanced()`，不平衡永不入库。

### 4.3 值对象 `LedgerEntry`（不可变分录）

- 字段：`postingId`、`accountId`（指向科目**实例**）、`direction`(DEBIT/CREDIT)、`amountMinor`、`currency`。
- 不变量：`amountMinor > 0`（负净额由规则反向成行）；重建走 `rehydrate(...)`；只可新增冲正。

### 4.4 两级科目（ADR-0078）

| 层 | 表 | 内容 |
|---|---|---|
| 科目定义 | `account_definitions` | `code`、名称、`type`(ASSET/LIABILITY/REVENUE/EXPENSE)、`normal_side`、`owner_dimension`(PLATFORM/MERCHANT/CHANNEL)、`status`——**目录**，migration/seed 管理 |
| 科目实例 | `accounts` | definition + owner（如 MERCHANT_PAYABLE:M001、CHANNEL_RECEIVABLE:ALIPAY）——**记账落点** |

8 个科目定义：CUSTOMER_CASH / MERCHANT_PAYABLE / FEE_REVENUE / SETTLEMENT_PAYABLE / SUSPENSE / CHANNEL_RECEIVABLE / BANK_CASH / CHANNEL_FEE_EXPENSE。`AccountResolver` 按 (definition, owner) 解析实例，未登记 owner 即拒绝。

### 4.5 账期 `LedgerPeriod`

`period`(YYYY-MM) × `currency` 一行，OPEN/CLOSING/CLOSED；`PeriodService` 关账前做试算平衡门禁（不平则拒关）。

## 5. 状态机（State Machine）

### 5.1 `Posting` / `LedgerEntry`

- `Posting.status` 当前只有 `POSTED` 一个落库值——落库即终态，**无后续迁移**；分录 `LedgerEntry` 为 **append-only**，一经提交不可 UPDATE/DELETE，更正只能新增 ADJUSTMENT 事件产生反向分录。
- 换言之：账本侧不存在「状态机会漂移」的对象，资金事实的修正必须是**新增事实**而非改写事实。

### 5.2 `LedgerPeriod`

| 迁移 | 触发 | 门禁 |
|---|---|---|
| `OPEN` → `CLOSING` | 关账请求（`POST /periods/{period}/close`） | 期间试算平衡（不平则拒关） |
| `CLOSING` → `CLOSED` | 试算平衡通过 | 关账后该期间不再接受新分录 |

## 6. 接口与事件契约（API / Event Contract）

### 6.1 端点（`/internal/ledger`，仅内部 Feign 调用）

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/accounting-events` | **唯一入账入口**（幂等回放）；不平衡由聚合根拒绝 |
| GET | `/postings?eventType=&sourceId=` | 按事件回查（重复投递判定「已入账」） |
| GET | `/postings/all?sourceType=` | 全量/按来源列交易 |
| GET | `/entries?sourceType=&sourceId=` | 按业务来源追溯全部分录（FR-008） |
| GET | `/balance` | 全局借贷平衡校验（FR-007），返回 `balanced` + `diffByCurrency` |
| GET | `/trial-balance?period=` | 期间试算平衡（科目 × 借贷发生额） |
| GET | `/balances?currency=` / `/accounts/{id}/balance` | 余额投影查询 |
| POST | `/balances/rebuild` | 余额投影重建（分录重放累加） |
| GET | `/periods` / POST `/periods/{period}/close` | 账期列表 / 关账（关账前试算平衡门禁） |

契约见 `common/common-dto/.../rpc/AccountingEventRequest.java` / `AccountingEventResponse.java` / `AccountCode.java`。

### 6.2 入站事件契约

唯一入站语言为 `AccountingEvent`，字段与金额口径见 [§4.1](#41-入站契约-accountingevent上游唯一语言)；六类事件的分录展开规则见 [§16](#16-六类事件的记账规则adr-0077-7账本决策)。

## 7. 运行链路（Runtime Flow）

### 7.1 记账引擎与幂等（`PostingEngine`）

`PostingEngine.post(AccountingEvent)` 单一入账管线：

1. **派生幂等键**并回查 `{eventType}:{sourceId}`，命中即回放首次结果（不重复入账）。
2. **规则展开**：`PostingRuleRegistry` 按 eventType 分发到 `PostingRule`（启动期 selfCheck：每个事件类型恰有一个规则、展开非空）。
3. **科目解析**：`AccountResolver` 把 `PostingLine`(科目码+owner) 解析成实例 id。
4. **聚合构造**：`Posting` 构造期平衡门禁；落库 `postings` + `ledger_entries`。
5. **余额投影**：同事务累加 `account_balances`（借方 +、贷方 − 的发生额视图，取号方向由 `BalanceChecker.signedBalance` 解释）。
6. **并发兜底**：撞 `uk_postings_idempotency_key`（`DuplicateKeyException`）→ 回查回放，只允许一个赢家。
7. **资金审计**：成功后经 `StructuredAuditLogger` 写 `ledger.posted` + `ledger.entries`；入账 HTTP 入口以 `TraceContext.runWithBizNo(sourceId)` 标注 bizNo。

## 8. 失败与恢复（Failure / Recovery）

- **不平衡即拒绝**：`Posting` 构造期 `requireBalanced()` 抛 `LEDGER_UNBALANCED`，**不落任何分录**（不会出现半套分录）。
- **重复投递**：幂等键命中或唯一约束冲突 → 回查回放首次结果，不产生第二套分录。
- **入账失败不回滚上游**：禁 2PC/XA（[§3.2](#32-硬约束constitution--adr)）；上游业务事实已成立，账本侧缺失由调用方补偿台账（`pending_postings`，归 Feature 034 / spec 034）与对账 T+1 审计 `MISSING_POSTING` 发现，人工调账收口。
- **投影漂移自愈**：`POST /balances/rebuild` 可全量重放分录重建 `account_balances`。
- 具体缺口见 [§15](#15-已知缺口known-gaps)。

## 9. 幂等 / 一致性 / 并发（Idempotency / Consistency / Concurrency）

### 9.1 幂等键由账本派生

- 幂等键 = `{eventType}:{sourceId}`（ADR-0077 D4），由账本派生，**上游不造幂等键**；`uk_postings_idempotency_key` 唯一约束兜底。
- 重复事件（含并发重复投递）只有一个赢家，其余回放首次结果。

### 9.2 一致性边界

- `postings` + `ledger_entries` + `account_balances` 在**同一本地事务**内提交；不存在「分录落了、投影没落」的中间态。
- 跨服务一致性靠 **同步 RPC + 幂等 + 调用方补偿台账**，不使用分布式事务（Constitution / ADR-0009 / 0018 / 0023）。

## 10. 数据与存储（Data / Storage）

### 10.1 持久化与 Schema（`09-ledger-schema.sql`）

Database-per-Service：账本服务自有 `ledger` 库（单机开发由 docker-compose 的 MySQL 8 实例承载）。

| 表 | 关键列 | 约束 |
|---|---|---|
| `account_definitions` | code, name, type, normal_side, owner_dimension, status | `uk_account_definitions_code`；8 科目 seed |
| `accounts` | definition_code, owner_type, owner_id, currency | `uk_accounts_instance`；科目实例 |
| `postings` | posting_no, event_type, idempotency_key, source_type, source_id, period, status, currency | `uk_postings_idempotency_key`（幂等兜底）、`idx_postings_source` |
| `ledger_entries` | posting_id, account_id, direction, amount_minor, currency | `idx_entries_posting/source/account` |
| `account_balances` | account_id, currency, debit_total, credit_total | `uk_account_balances`；同事务投影 |
| `ledger_periods` | period, currency, status | `uk_ledger_periods` |

仓储边界 `LedgerRepository` / `AccountRepository` / `AccountBalanceRepository` / `LedgerPeriodRepository`（领域接口）由 Mybatis 实现落库，`InMemory*` 实现供单测；`BalanceChecker` 提供 `isBalanced()` / `trialBalance(period)` / `balanceOf(instanceId, currency)` / `balances(currency)`。

`deployment/schema/031-ledger-accounting-foundation.sql` 为存量库增量迁移（补 definitions / balances / periods 三表 + `payments.merchant_id` 之外的账本侧变更），`demo/reset.sh` 会重放 `09`（幂等）回灌科目种子。

## 11. 可观测性与安全（Observability / Security）

### 11.1 审计与安全口径

- **资金审计（FR-011）**：每笔成功记账经 `StructuredAuditLogger` 写 `ledger.posted` + `ledger.entries`；入账入口以 `TraceContext.runWithBizNo(sourceId)` 标注 bizNo，日志可据 `sourceId` 串联。
- **仅内部可达**：端点全部位于 `/internal/ledger`，只被其他服务经 Feign 调用，不暴露公网；入站内部鉴权按项目既有裁剪口径为预留空实现（见 [technical-solution.md §2.4](../technical-solution.md)）。
- **敏感数据脱敏本期不做**（ADR-0027），接入真实渠道前 MUST 重新引入。

## 12. 部署（Deployment）

- **进程与端口**：`ledger-service` :8090；单机多进程部署，独立进程 / 独立端口 / 独立部署单元（整体部署形态见 [technical-solution.md §6](../technical-solution.md) 与 [diagrams/08-deployment](../diagrams/08-deployment.puml)）。
- **中间件依赖**：MySQL 8（自有 `ledger` 库）；无 Redis、无 MQ 依赖。
- **启动顺序**：MySQL 就绪 → 重放 `09-ledger-schema.sql`（幂等）+ `031-ledger-accounting-foundation.sql` → 服务启动（`PeriodService` / `PostingRuleRegistry` 启动期自检）。

## 13. 测试与验证（Testing / Verification）

### 13.1 验收覆盖（2026-09-21，Feature 031）

| 测试类 | 覆盖点 |
| --- | --- |
| `PaymentCapturePostingTest` / `RefundPostingTest` / `SettlementPostingTest` / `AdjustmentPostingTest` | 六类规则分录逐行断言 + 案例六余额终态 |
| `PostingIdempotencyTest` | 必测⑤⑥：重复回放、并发唯一赢家、派生幂等键 |
| `PostingBalanceGateTest` / `PostingRuleRegistryTest` | 不平衡构造期拒绝；规则自检 |
| `BalanceCheckerTest` / `PeriodCloseTest` | 投影余额、试算平衡、关账门禁 |
| `SourceTraceabilityTest` | 按 (sourceType, sourceId) 反查不串源 |
| `LedgerPipelineIntegrationTest` / `LedgerPostingAtomicityTest` | 真 MySQL 管线 / 失败不落半套分录 |
| `LedgerPostingConcurrencyTest` / `LedgerPostingRaceTest` | 并发重复入账（Testcontainers） |
| `AccountingVocabularyBoundaryTest` | ArchUnit：调用方不得再出现科目/借贷词表 |

> 测试源码位于 [`ledger-service/src/test/java/`](../../../ledger-service/src/test/java/)；架构不变量在 `deployment/architecture-tests` 的 ArchUnit 规则中执行（`./mvnw -B verify`）。

## 14. 相关文档（Related Documents）

### 14.1 本文明文引用的 ADR

`ADR-0008`、`ADR-0009`、`ADR-0011`、`ADR-0018`、`ADR-0023`、`ADR-0027`、`ADR-0034`、`ADR-0058`、`ADR-0077`、`ADR-0078`、`ADR-0079`

> 完整索引见 [docs/adr/README.md](../../adr/README.md)，落点追溯见 [docs/adr/traceability.md](../../adr/traceability.md)。

### 14.2 本文明文引用的 Spec

`spec 031`、`spec 032`、`spec 033`、`spec 034`

> Spec 索引见 [docs/specs/README.md](../../specs/README.md)。

### 14.3 相关图

- [07-ledger-reconciliation-flow](../diagrams/07-ledger-reconciliation-flow.puml)（Dynamic / Domain）
- [01-system-context](../diagrams/01-system-context.puml)（C4 L1）
- [02-container-overview](../diagrams/02-container-overview.puml)（C4 L2）
- [08-deployment](../diagrams/08-deployment.puml)（C4 Deployment）

### 14.4 关联决策与功能需求（原文）

- **ADR-0077~0079**（🟢 Accepted，2026-09-21 负责人裁决 D-1~D-7 按推荐方案）：契约事件化 / 两级科目迁移 / 余额投影与期间；**Partially Supersedes ADR-0008**（被取代仅「分录由调用方组装」与「固定科目枚举」两条；复式结构、平衡门禁、append-only、幂等范式保留）。
- **ADR-0009 / 0018 / 0023**：同步 RPC + 禁 2PC + 失败不回滚上游事实，语义不变（契约描述随 031 修订）。
- **ADR-0011**：渠道清算**科目**本次引入（CHANNEL_RECEIVABLE / BANK_CASH / CHANNEL_FEE_EXPENSE），清算**链路**归 032+。
- **spec 031**：`docs/specs/stage-05-channel-and-finance-deepening/031-ledger-accounting-foundation/spec.md`（十项原则、必测清单）。

### 14.5 关联文档

- [technical-solution.md](../technical-solution.md)（全局当前事实）
- [roadmap.md](../roadmap.md)（阶段与状态）
- [system-design-standard.md](../../standards/system-design-standard.md)（本文件遵循的规范）
- [code-debt-backlog.md](../../operations/code-debt-backlog.md)（已知缺口台账）

## 15. 已知缺口（Known Gaps）

### 15.1 当前缺口登记

- **产生方未切换**：CHANNEL_SETTLEMENT / MERCHANT_SETTLEMENT / ADJUSTMENT 规则与解析已就绪，但产生方切换随 032/034 推进。
- **schema 双路径重放门禁与 L2b 扩面**归 033。
- 与 ledger-service 相关的其他已知缺口见 [code-debt-backlog.md](../../operations/code-debt-backlog.md) 与 [stage-05 design-review.md](../../specs/stage-05-channel-and-finance-deepening/design-review.md)。

## 16. 六类事件的记账规则（ADR-0077 §7，账本决策）

| 事件 | 产生方（幂等键 sourceId） | 分录 |
|---|---|---|
| PAYMENT_CAPTURE | payment（paymentNo） | Dr CHANNEL_RECEIVABLE:渠道 毛额 / Cr MERCHANT_PAYABLE:商户 毛额；商户费 >0：Dr MERCHANT_PAYABLE / Cr FEE_REVENUE；渠道费 >0：Dr CHANNEL_FEE_EXPENSE:渠道 / Cr CHANNEL_RECEIVABLE:渠道 |
| REFUND | payment·refund 域（refundNo） | 上面三条的反向（毛额对冲 + 已确认费用退还红字；不猜费用，上游确认才红字） |
| CHANNEL_FEE | 032 对账（CF-{channel}-{period}） | Dr CHANNEL_FEE_EXPENSE:渠道 / Cr CHANNEL_RECEIVABLE:渠道 |
| CHANNEL_SETTLEMENT | 032 对账（CS-{channel}-{period}） | 净额 >0：Dr BANK_CASH / Cr CHANNEL_RECEIVABLE:渠道；<0 反向 |
| MERCHANT_SETTLEMENT | settlement（batchNo） | 净额 >0：Dr MERCHANT_PAYABLE:商户 / Cr SETTLEMENT_PAYABLE:商户；<0 反向；=0 拒绝 |
| ADJUSTMENT | reconciliation·audit（AB/AD 单号） | 指定 `fromAccountCode` → `toAccountCode`，可携带 `reverses*` 冲正引用 |

> 031 阶段 PAYMENT_CAPTURE/REFUND/CHANNEL_FEE 已接入；CHANNEL_SETTLEMENT/MERCHANT_SETTLEMENT/ADJUSTMENT 规则与解析就绪，产生方切换随 032/034 推进。
