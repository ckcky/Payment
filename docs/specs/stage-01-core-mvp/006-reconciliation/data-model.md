# Data Model: Reconciliation 对账（缺口补齐）

**Feature**: `006-reconciliation` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> 本文件定义对账领域实体、状态机与不变量。**已实现部分保持不变**，仅对缺口 G1（生命周期闭合）与 G2（按周期账单来源）相关的字段/行为做扩展。金额一律 `long` 最小货币单位（`amountMinor`），禁 `float`/`double`。

## 1. 实体关系（MVP，保持不变）

```text
ReconciliationBatch (1) ── (N) Match        （JSON 内嵌 matches_json）
ReconciliationBatch (1) ── (N) Difference   （JSON 内嵌 differences_json）
ReconciliationBatch (1) ── (1) ChannelStatementSource  （本次账单来源，新增列 statement_source）
```

- 匹配/差异**不拆表**（沿用既有决策，见 `07-reconciliation-schema.sql:3` 注释）；本 Feature 不引入子表。
- `PlatformFact` / `ChannelStatement` 为**临时输入**（不持久化），只有比对结果（Match/Difference）随批次落库。

## 2. ReconciliationBatch（对账批次，聚合根）

**存储**：`reconciliation_batches`（`deployment/schema/07-reconciliation-schema.sql`）

| 字段 | 类型 | 现状 | 说明 |
|---|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | 已实现 | 批次 ID |
| period | VARCHAR(32) NOT NULL | 已实现 | 对账周期，唯一 `uk_reconciliation_batches_period` |
| source | VARCHAR(32) NOT NULL | 已实现 | 渠道来源（当前固定 `mock-channel`） |
| status | VARCHAR(32) NOT NULL | 已实现 | `ReconciliationStatus` 枚举名 |
| matches_json | TEXT | 已实现 | 一致匹配 JSON |
| differences_json | TEXT | 已实现 | 差异 JSON（含 `resolutionStatus`/`resolutionNote`） |
| **statement_source** | **VARCHAR(255) NULL** | **新增（G2）** | 本次实际使用的渠道账单来源（`ChannelStatementSource` 序列化），供追溯与「回退不静默」 |
| **closed_at** | **DATETIME NULL** | **新增（G1）** | 关闭时间；未关闭为 NULL |
| **closed_by** | **VARCHAR(64) NULL** | **新增（G1）** | 关闭操作人 |
| created_at / updated_at / created_by / updated_by / version | — | 已实现 | 审计 + 乐观锁（BaseEntity） |

**DDL 变更（非破坏性，Constitution §8.3 范围）**：

```sql
ALTER TABLE reconciliation_batches
    ADD COLUMN statement_source VARCHAR(255) NULL COMMENT '本次对账实际使用的渠道账单来源（JSON）' AFTER source,
    ADD COLUMN closed_at DATETIME NULL COMMENT '批次关闭时间（CLOSED 时非空）' AFTER status,
    ADD COLUMN closed_by VARCHAR(64) NULL COMMENT '批次关闭操作人' AFTER closed_at;
```

> 存量数据：`statement_source` 为 NULL（表示「来源未知/历史批次」），`closed_at`/`closed_by` 为 NULL。**不做回填**（历史批次本就无该信息，回填等于伪造事实）。

### 2.1 状态机（G1 接线后）

```text
PENDING --start--> RECONCILING --finish(无差异)--> CONSISTENT --close--> CLOSED
                         |
                         \--finish(有差异)--------> HAS_DIFFERENCE --beginProcessing--> PROCESSING --close--> CLOSED
```

| 迁移 | 领域方法 | 前置 | 幂等性（本 Feature 变更） |
|---|---|---|---|
| `PENDING → RECONCILING` | `start()` (`ReconciliationBatch.java:50`) | PENDING | 否（重复调用抛 `STATE_TRANSITION_VIOLATION`） |
| `RECONCILING → CONSISTENT / HAS_DIFFERENCE` | `finish()` (`:56`) | RECONCILING | 否 |
| `HAS_DIFFERENCE → PROCESSING` | `beginProcessing()` (`:66`) | **HAS_DIFFERENCE 或 PROCESSING** | **是（新增）**：已在 `PROCESSING` 时为空操作，便于「处理第 2、3 条差异」不报错 |
| `CONSISTENT / PROCESSING → CLOSED` | `close()` (`:72`) | CONSISTENT / PROCESSING / **CLOSED** | **是（新增）**：已 `CLOSED` 时空操作（幂等吸收） |
| 关闭门禁 | `close()` 内 | **未处理差异数 = 0** | 不为 0 抛 `UNRESOLVED_DIFFERENCES`（新增错误码） |

> 现有测试 `ReconciliationBatchStateMachineTest.beginProcessingWithoutDifferencesIsIllegal`（`:76-81`）断言 `CONSISTENT.beginProcessing()` 抛异常 —— 幂等扩展**仅新增** `PROCESSING → PROCESSING` 与 `CLOSED → CLOSED`，不破坏该断言（Constitution §VIII.4：不改测试迎合实现）。

### 2.2 批次级计算属性（领域方法，不落列）

| 属性 | 计算 | 用途 |
|---|---|---|
| `unresolvedDifferenceCount` | `differences.stream().filter(d -> !d.isResolved()).count()` | 关闭门禁；`settlement-summary` 口径（`ReconciliationApplicationService.java:127`）；批次响应暴露（N4） |
| `differenceAmountMinor` | `Σ abs(platformAmountMinor - channelAmountMinor)`，单侧缺失取该侧金额 | 差异金额指标（Constitution §VII.1：对账需「差异数量/差异金额」） |

## 3. Difference（差异，随批次 JSON 内嵌）

**存储**：`differences_json`（Jackson 序列化，`MybatisReconciliationRepository.java:110-138`）

| 字段 | 类型 | 现状 | 说明 |
|---|---|---|---|
| reference | String NOT NULL | 已实现 | 对账引用（渠道 reference） |
| type | enum NOT NULL | 已实现 | `AMOUNT_MISMATCH` / `STATUS_MISMATCH` / `PLATFORM_ONLY` / `CHANNEL_ONLY` |
| platformAmountMinor | Long NULL | 已实现 | 平台侧金额（`PLATFORM_ONLY` 时渠道侧为 null，反之亦然） |
| channelAmountMinor | Long NULL | 已实现 | 渠道侧金额 |
| platformStatus | String NULL | 已实现 | 平台侧状态 |
| channelStatus | String NULL | 已实现 | 渠道侧状态 |
| resolutionStatus | String NULL | 已实现 | null（未处理）/ `RESOLVED`（`Difference.java:17`） |
| resolutionNote | String NULL | 已实现（**新增必填校验**） | 人工处理依据；本 Feature 起 MUST 非空 |
| **resolvedAt** | **String(ISO-8601) NULL** | **新增（N3）** | 处理时间，随 JSON 内嵌（不加 DB 列） |
| **resolvedBy** | **String NULL** | **新增（N3）** | 处理人（操作人标识） |

**处理入口变更（G1 + N3）**：

- 领域：`Difference.resolve(note, actor, at)`（替代/重载现有 `resolve(note)`，`Difference.java:56`）——MUST 校验 `note` 非空白，否则抛 `INVALID_ARGUMENT`；已 `RESOLVED` 的差异重复处理为**幂等空操作**（不改已有 `resolutionNote`？——决策：**覆盖为最新依据并刷新 `resolvedAt`/`resolvedBy`**，因为「重复处理」是运维补充依据的合理场景；ADR-0019 待确认）。
- 应用：`resolveDifference(batchId, reference, note, actor)` 在 `Difference.resolve` 之后调用 `batch.beginProcessing()`，再 `repository.save(batch)`（`ReconciliationApplicationService.java:104-115`）。

## 4. Match（一致匹配，随批次 JSON 内嵌）

| 字段 | 类型 | 现状 | 说明 |
|---|---|---|---|
| reference | String | 已实现 | 对账引用 |
| type | String | 已实现 | `PAYMENT` / `REFUND` |
| amountMinor | long | 已实现 | 一致金额（最小货币单位） |
| currencyCode | String | 已实现 | 币种（MVP：CNY） |

> 结算侧直接取 `amountMinor` 计算净额，无需回查原始事实（`ReconciliationApplicationService.java:122-126`）。本 Feature 不改 `Match` 结构。

## 5. ChannelStatementSource（账单来源，新增值对象 · G2）

| 字段 | 类型 | 说明 |
|---|---|---|
| sourceType | String | 固定 `FIXTURE`（MVP；未来真实渠道为 `SFTP`/`API`） |
| locator | String | 定位符，如 `fixtures/channel-statements/2026-08.csv` |
| entryCount | int | 本次加载的账单条目数 |
| fallbackUsed | boolean | 是否发生回退（未命中周期 fixture 而用默认 fixture） |

**落库**：序列化为 `statement_source` 列（VARCHAR(255)）。**回退 MUST 留痕**：`fallbackUsed=true` 时递增 `reconciliation.statement_fallback` 并打印 WARN 日志（含 `period` 与 `locator`）。

### 5.1 加载策略（ADR-0020）

```text
load(period):
  1. 尝试 classpath: {fixture-dir}/{period}.csv        命中 → fallbackUsed=false
  2. 未命中 → 加载 {fixture-dir}/sample.csv            fallbackUsed=true（留痕）
  3. 两者皆无 → INTERNAL_ERROR（保留既有行为，CsvChannelStatementLoader.java:31）
```

- `period` 为安全输入：MUST 校验仅含 `[A-Za-z0-9._-]`，防止路径穿越（Constitution Security）。
- 非法行（列数 < 4 或金额非数字）MUST 显式记录（WARN + 行号），**MUST NOT** 静默跳过（FR-018）。

## 6. 不变量（Invariants）

| # | 不变量 | 保障机制 | 关联缺口 |
|---|---|---|---|
| **INV-1** | 金额全程 `long` 分或 `BigDecimal`（明确 scale），**禁** `float`/`double` | 类型约束 + Code Review + 静态检视 | FR-005 |
| **INV-2** | 批次状态迁移只经 `start`/`finish`/`beginProcessing`/`close` 四个领域方法，禁散落 `setStatus` | `requireStatus`（`ReconciliationBatch.java:80`） | G1 / FR-016 |
| **INV-3** | `CLOSED` 为只读终态：不可再处理差异、不可再变更匹配/差异；重复关闭幂等吸收 | `close()` 幂等 + 应用层前置校验 | G1 / FR-010 |
| **INV-4** | 关闭前置：`unresolvedDifferenceCount == 0`，否则拒绝关闭 | `close()` 内门禁 | G1 / FR-009 |
| **INV-5** | 周期幂等：`(period)` 全局唯一，同周期重复执行返回首次批次 | `uk_reconciliation_batches_period` + `findByPeriod` 回查 + `DuplicateKeyException` 捕获（`:61/:86-94`） | 已实现 |
| **INV-6** | 原始事实零回写：reconciliation 对 payment/refund 只有 `GET` 路径，无任何写端点 | 代码级仅两个 `@GetMapping` Feign 接口 | FR-002 / SC-005 |
| **INV-7** | 账单来源可追溯且**回退不静默**：每次对账 MUST 记录 `statement_source`；`fallbackUsed=true` MUST 有指标 + WARN | `ChannelStatementSource` 必填 + 埋点 | G2 / FR-003 |
| **INV-8** | 事实读取失败 ⇒ **不入批**（无半成品批次），该周期可被安全重跑 | 读 RPC 在 `insertNew` 之前；异常上抛不入事务落库 | G3 / FR-014 |
| **INV-9** | 匹配确定性：同输入 ⇒ 同输出（`TreeSet` 有序引用 + 纯函数，无外部依赖） | `ReconciliationMatching.java:23-51` | FR-004 |
| **INV-10** | 并发更新保护：`version` 乐观锁，`updateById` 0 行 ⇒ `CONFLICT`（`MybatisReconciliationRepository.java:76`） | MyBatis-Plus 乐观锁 | FR-016 |
| **INV-11** | 差异处理依据完整：`resolutionNote` 非空 + `resolvedBy` + `resolvedAt` 三者同时写入 | `Difference.resolve(note, actor, at)` 校验 | N3 / FR-007 |
| **INV-12** | 结算口径一致：`unresolvedDifferenceCount` 在 `settlement-summary` 与批次响应中**同源同值**；`> 0` ⇒ `SettlementEligibility` 拒绝结算 | 单一计算方法 `batch.unresolvedCount()` | N4 / FR-017 |

## 7. 索引策略（保持不变）

- `uk_reconciliation_batches_period`：周期幂等兜底（INV-5）。
- 匹配/差异内嵌 JSON，**不新增**子表与索引（避免跨表一致性成本）。
- `[待定]` 若未来需要「按状态查未关闭批次」或「按周期区间统计差异金额」，再评估 `idx_batches_status` / `idx_batches_period`（`findByPeriodBetween` 已存在，当前无索引，数据量小可忽略）。

## 8. 错误码（对账服务新增/沿用）

| 错误码 | 语义 | 本 Feature 使用场景 |
|---|---|---|
| `INVALID_ARGUMENT` | 参数非法 | `period` 空白（`ReconciliationBatch.java:28`）；`resolutionNote` 空白（**新增**，INV-11）；`period` 含非法字符（路径穿越防护） |
| `NOT_FOUND` | 资源不存在 | 批次/差异不存在；周期无批次 |
| `UNRESOLVED_DIFFERENCES` | **新增** | 存在未处理差异时请求关闭（INV-4） |
| `STATE_TRANSITION_VIOLATION` | 非法状态迁移 | 非 `CONSISTENT/PROCESSING` 关闭；`CLOSED` 后处理差异（INV-3） |
| `CONFLICT` | 并发冲突 | 乐观锁 0 行命中（INV-10） |
| `INTERNAL_ERROR` | 内部错误 | 账单 fixture 缺失/读取失败；事实读取失败（归一化后） |

## 9. 埋点（新增/沿用）

| 指标键 | 类型 | 维度 | 现状 | 说明 |
|---|---|---|---|---|
| `reconciliation.run` | counter | module | 已实现 | 执行对账批次 |
| `reconciliation.difference` | counter | module, type | 已实现 | 差异条数（按四类） |
| `reconciliation.difference_amount_minor` | counter/summary | module, type | **新增** | 差异金额（SC-007） |
| `reconciliation.difference_resolved` | counter | module | **新增** | 差异处理次数（INV-11） |
| `reconciliation.batch_closed` | counter | module | **新增** | 批次关闭次数（G1） |
| `reconciliation.statement_fallback` | counter | module | **新增** | 账单回退次数（INV-7） |
| `reconciliation.fact_read_failed` | counter | module, target=payment\|refund | **新增** | 事实读取失败（G3 / INV-8） |

**审计**：差异处理与批次关闭 MUST 写 `FINANCIAL_AUDIT`（`StructuredAuditLogger`），字段：`traceId`、`period`、`batchId`、`reference`、`fromStatus`/`toStatus`、`operator`、`resolutionNote`。对账**执行**本身为只读比对，不写资金审计（避免审计噪音）。
