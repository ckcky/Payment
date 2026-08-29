# Data Model: Settlement 结算（缺口补齐）

**Feature**: `007-settlement` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> 本文件定义结算领域实体、状态机与不变量。**已实现部分保持不变**，仅对 G1（调整项）、G2（闸门证据）、N3（close）相关的字段/行为做扩展。金额一律 `long` 最小货币单位（`*Minor`），禁 `float`/`double`。

## 1. 实体关系（扩展后）

```text
SettlementBatch (1) ──── (N) SettlementItem          （settlement_items，类型 PAYMENT / REFUND / ADJUSTMENT）
SettlementBatch (1) ──── (1) ConfirmedFactSnapshot   （闸门证据：fact_count / source_period，新增列）
SettlementAdjustment (N) ─ (0..1) SettlementBatch    （settlement_adjustments，新增表；建批时按 (merchant, period) 汇总）
```

- `SettlementItem` **不拆表**（明细随聚合 1:N 读写，沿用既有决策，见 `08-settlement-schema.sql:29`）。
- `SettlementAdjustment` **有独立生命周期**（先于批次登记），故**独立建表**，不内嵌批次。
- `SettlementFact`（来自 reconciliation）为**临时输入**，不持久化；只有「参与计算的结果」（金额 + 明细）随批次落库。

## 2. SettlementBatch（结算批次，聚合根）

**存储**：`settlement_batches`（`deployment/schema/08-settlement-schema.sql`）

| 字段 | 类型 | 现状 | 说明 |
|---|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | 已实现 | 批次 ID |
| merchant_id | VARCHAR(32) NOT NULL | 已实现 | 商户引用，**唯一约束组成部分** |
| period | VARCHAR(32) NOT NULL | 已实现 | 结算周期，**唯一约束组成部分** |
| currency_code | VARCHAR(8) NOT NULL | 已实现 | 币种（MVP 固定 `CNY`；币种不一致显式拒绝，不做清分） |
| income_minor | BIGINT NOT NULL | 已实现 | 收入合计（`type=PAYMENT` 求和，≥ 0） |
| refund_minor | BIGINT NOT NULL | 已实现 | 退款合计（`type=REFUND` 求和，≥ 0） |
| adjustment_minor | BIGINT NOT NULL | **语义变更（G1）** | 调整合计，**带符号**：`CREDIT(补差) 为正 / DEBIT(扣款) 为负`；恒等于本批次 `ADJUSTMENT` 明细带符号求和 |
| net_minor | BIGINT NOT NULL | **公式变更（G1）** | `net = income − refund + adjustment`（ADR-0022 选定后生效；可为负） |
| status | VARCHAR(32) NOT NULL | 已实现 | `SettlementStatus` 枚举名 |
| idempotency_key | VARCHAR(128) NOT NULL | 已实现 | 幂等键，唯一 `uk_settlement_batches_idempotency_key` |
| **fact_count** | **INT NOT NULL DEFAULT 0** | **新增（G2）** | 参与本次净额计算的事实条数（闸门证据：明细条数 = 事实条数 + 调整项条数） |
| **source_period** | **VARCHAR(32) NULL** | **新增（G2）** | reconciliation 汇总回传的 `period`（必须与请求 `period` 一致，否则拒绝） |
| created_at / updated_at / created_by / updated_by / version | — | 已实现 | 审计 + 乐观锁（BaseEntity） |

**唯一约束（已实现，保持不变）**：`uk_settlement_batches_merchant_period`、`uk_settlement_batches_idempotency_key`。

**DDL 变更（Constitution §8.3 范围，须确认）**：

```sql
ALTER TABLE settlement_batches
    ADD COLUMN fact_count INT NOT NULL DEFAULT 0 COMMENT '参与净额计算的事实条数（闸门证据）' AFTER net_minor,
    ADD COLUMN source_period VARCHAR(32) NULL COMMENT '对账汇总回传周期（须与请求周期一致）' AFTER fact_count;
```

> 存量数据：`fact_count` 取默认值 0、`source_period` 为 NULL，表示「历史批次未记录闸门证据」。**不做回填**（回填等于伪造事实，同 `006-reconciliation` 的取舍）。

### 2.1 状态机（N3 接线后）

```text
PENDING --calculate--> CALCULATING --markReady--> READY --execute--> EXECUTING
       EXECUTING --succeed------------> SUCCEEDED --close--> CLOSED
       EXECUTING --fail---------------> FAILED    --close--> CLOSED
       EXECUTING --markUnknown--------> UNKNOWN --succeed/fail--> SUCCEEDED/FAILED
```

| 迁移 | 领域方法 | 前置 | 幂等性 | 现状 |
|---|---|---|---|---|
| `PENDING → CALCULATING` | `calculate()` (`SettlementBatch.java:62`) | PENDING | 否（重复调用抛错） | 已实现 |
| `CALCULATING → READY` | `markReady()` (`:69`) | CALCULATING | 否 | 已实现 |
| `READY → EXECUTING` | `execute()` (`:75`) | READY | 否 | 已实现 |
| `EXECUTING/UNKNOWN → SUCCEEDED` | `succeed()` (`:81`) | EXECUTING / UNKNOWN | 是（同态返回 false） | 已实现 |
| `EXECUTING/UNKNOWN → FAILED` | `fail(reason)` (`:87`) | EXECUTING / UNKNOWN | 是 | 已实现 |
| `EXECUTING → UNKNOWN` | `markUnknown(reason)` (`:93`) | EXECUTING | 是 | 已实现（模拟执行强制走此路，`:93-96`） |
| `SUCCEEDED/FAILED → CLOSED` | `close()` (`:98`) | SUCCEEDED / FAILED | **是（N3：需端点可达）** | **领域已实现，应用层零调用** |
| 终态吸收 | `transitionTo` (`:131-145`) | 任一终态 | 迟到冲突结果返回 `false`，不抛错 | 已实现 |

**本 Feature 的状态机变更（Constitution §8.8，须确认）**：仅新增 `close()` 的**可达端点**（领域语义不变）；`UNKNOWN`/`EXECUTING` 关闭仍被拒（`STATE_TRANSITION_VIOLATION`）。

## 3. SettlementItem（明细值对象）

**存储**：`settlement_items`

| 字段 | 类型 | 现状 | 说明 |
|---|---|---|---|
| id / batch_id | BIGINT | 已实现 | 批次引用，`idx_settlement_items_batch_id` |
| reference | VARCHAR(64) NOT NULL | 已实现 | 事实引用；`ADJUSTMENT` 明细取**调整项业务编号** |
| type | VARCHAR(16) NOT NULL | **语义扩展（G1）** | `PAYMENT` / `REFUND` / **`ADJUSTMENT`（本 Feature 起真实产生）** |
| amount_minor | BIGINT NOT NULL | **语义扩展（G1）** | `PAYMENT` / `REFUND` 为正；**`ADJUSTMENT` 带符号**（CREDIT 正 / DEBIT 负） |
| currency_code | VARCHAR(8) NOT NULL | 已实现 | 必须等于批次币种（币种不一致在闸门层即拒绝） |

## 4. SettlementAdjustment（调整项实体，新增）

**存储**：`settlement_adjustments`（新增表，Constitution §8.3）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | 调整项 ID |
| merchant_id | VARCHAR(32) NOT NULL | 商户引用（与 `period` 共同定位结算范围） |
| period | VARCHAR(32) NOT NULL | 结算周期 |
| direction | VARCHAR(16) NOT NULL | `CREDIT`（补差，增加净额）/ `DEBIT`（扣款，减少净额） |
| amount_minor | BIGINT NOT NULL | 调整金额，**恒 > 0**（方向由 `direction` 表达，禁止用负数金额表达方向） |
| currency_code | VARCHAR(8) NOT NULL | 币种（MVP `CNY`，必须与批次币种一致） |
| reason | VARCHAR(512) NOT NULL | 调整理由（人工依据，**MUST 非空**） |
| operator | VARCHAR(64) NOT NULL | 登记操作人（**MUST 非空**） |
| idempotency_key | VARCHAR(128) NOT NULL | 幂等键，唯一 `uk_settlement_adjustments_idem` |
| status | VARCHAR(16) NOT NULL | `ACTIVE`（参与计算）/ `REVOKED`（已撤销，不参与计算） |
| created_at / updated_at / created_by / updated_by / version | — | 审计 + 乐观锁（BaseEntity） |

```sql
CREATE TABLE IF NOT EXISTS settlement_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id VARCHAR(32) NOT NULL,
    period VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_adjustments_idem (idempotency_key),
    KEY idx_settlement_adjustments_scope (merchant_id, period, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> 现行 `Adjustment` 记录（`domain/Adjustment.java:6`）为**零引用死代码**：本 Feature 以 `SettlementAdjustment` 实体取代之；是否**删除** `Adjustment.java` 由 ADR-0022 确认（Constitution §VIII.2「一次改动只做一件事」——若本 Feature 未授权清理，则保留并在注释中标注废弃）。

## 5. ConfirmedFactSnapshot（闸门证据，新增列承载）

| 字段 | 载体 | 说明 |
|---|---|---|
| `fact_count` | `settlement_batches.fact_count` | 参与净额计算的事实条数；用于校验「明细条数 = 事实条数 + 调整项条数」，杜绝「进明细不进金额」（G2） |
| `source_period` | `settlement_batches.source_period` | reconciliation 汇总回传的周期，用于校验来源周期与请求周期一致 |

## 6. 不变量（Invariants）

| # | 不变量 | 强制位置 | 违反后果 |
|---|---|---|---|
| INV-1 | 金额全程 `long` 最小货币单位，**MUST NOT** 出现 `float`/`double` | 全链路（Constitution §II.1） | 代码检视 + 测试 |
| INV-2 | `netMinor = incomeMinor − refundMinor + adjustmentMinor`（ADR-0022 选定公式） | `SettlementBatch.compute` | `AMOUNT_INVARIANT_VIOLATION` |
| INV-3 | `incomeMinor >= 0` 且 `refundMinor >= 0`；`adjustmentMinor` 可正可负 | `SettlementBatch.compute` | `AMOUNT_INVARIANT_VIOLATION` |
| INV-4 | `adjustmentMinor == sum(ADJUSTMENT 明细带符号金额)`；`incomeMinor == sum(PAYMENT 明细)`；`refundMinor == sum(REFUND 明细)` | `SettlementBatch.compute` / 建批编排 | `AMOUNT_INVARIANT_VIOLATION`（G2/N3 相关） |
| INV-5 | 明细条数 = `fact_count` + 调整项条数（**零**「在明细里不在金额里」的事实） | 建批编排 | `AMOUNT_INVARIANT_VIOLATION` |
| INV-6 | 每条事实：`type ∈ {PAYMENT, REFUND}`、`amountMinor >= 0`、`currencyCode == 批次币种`；`summary.period == 请求 period` | `ConfirmedFactGate` | 拒绝建批 + `settlement.gate_rejected`（FR-007/FR-008） |
| INV-7 | 调整项：`amountMinor > 0`、`reason` 非空、`operator` 非空；同 `(merchant, period)` 已存在批次时禁止新登记 | `SettlementAdjustment` / 登记编排 | `INVALID_ARGUMENT` / `STATE_TRANSITION_VIOLATION`（FR-002/FR-006） |
| INV-8 | 幂等：三处唯一约束（`uk_settlement_batches_idempotency_key`、`uk_settlement_batches_merchant_period`、`uk_settlement_adjustments_idem`）兜底；重复请求返回首次结果 | 仓储 + 应用服务 | 撞键回查返回首次；同键不同参数报 `DUPLICATE`（FR-011） |
| INV-9 | 幂等键命中时 `merchantId`/`period` 必须与请求一致 | `SettlementApplicationService` | `DUPLICATE`（N5，当前为静默返回） |
| INV-10 | 状态迁移只经 `SettlementBatch` 集中方法，**MUST NOT** 散落 `setStatus`；并发更新由 `version` 乐观锁保护 | 领域层 | 非法迁移 `STATE_TRANSITION_VIOLATION`；并发 `CONFLICT`（FR-013） |
| INV-11 | 终态（`SUCCEEDED`/`FAILED`/`CLOSED`）吸收迟到冲突结果；`CLOSED` 为只读终态 | `transitionTo` | 返回 `false`（幂等吸收） |
| INV-12 | **无真实出款**：代码级不存在银行/渠道出款路径；模拟执行强制进 `UNKNOWN` | `createBatch` | Roadmap Phase 7 硬约束（FR-020） |
| INV-13 | 记账（若 ADR-0023 采纳）：仅 `SUCCEEDED` 且 `netMinor > 0` 才发起；幂等键 `SETTLEMENT:<batchId>`；借贷平衡由 `ledger-service` 强校验 | `LedgerPostingGateway` / 账本 `Posting` | 记账失败**不回滚**批次，记 `ledger.posting_failed` 进待记账兜底（FR-017/FR-018） |
| INV-14 | 币种单一：MVP 仅 `CNY`；币种不一致**显式拒绝**，**MUST NOT** 静默相加 | `ConfirmedFactGate` / 登记编排 | `AMOUNT_INVARIANT_VIOLATION`（FR-022） |
| INV-15 | 零回写：settlement 对 reconciliation / payment / refund 只有 `GET` 读路径 | 代码级 | 集成测试断言事实快照不变（FR-010） |
| INV-16 | Database-per-service：仅读写 `settlement` Schema，**MUST NOT** 跨服务 SQL | 代码级 | 代码检视（FR-021） |
| INV-17 | 出站 RPC 显式超时（`[目标]` connect 1s / read 3s）；仅对幂等只读 GET 有限重试（≤ 3 次 / 1s-2s-4s） | `FeignResilienceConfig` | Constitution §V.6（N4，当前违反） |
| INV-18 | 关键动作（登记调整、建批、UNKNOWN、失败、关闭、记账）MUST 有 `FINANCIAL_AUDIT`（含 `traceId`、操作人、理由、前后状态）与指标 | 应用服务 | 测试断言审计条数（FR-016） |

## 7. 调整额计算示例（G1 落地后的口径）

```text
事实（来自 reconciliation settlement-summary，周期 2026-08）：
  PAYMENT  ref-1  +5000 CNY
  PAYMENT  ref-2  +3000 CNY
  REFUND   ref-3  -1000   → refund_minor = 1000（正值，由公式减）
调整项（settlement_adjustments，merchant=M1, period=2026-08, status=ACTIVE）：
  CREDIT   adj-1   500 CNY  平台赔付补差   → +500
  DEBIT    adj-2   300 CNY  客诉扣款       → -300

income_minor     = 5000 + 3000 = 8000
refund_minor     = 1000
adjustment_minor = (+500) + (-300) = +200
net_minor        = 8000 − 1000 + 200 = 7200

settlement_items 共 5 条：PAYMENT×2、REFUND×1、ADJUSTMENT×2（amount 分别 +500 / −300）
fact_count = 3，明细条数 = 3 + 2 = 5（INV-5 成立）
```

## 8. 待定项（不静默实现）

| 项 | 说明 | 归属 |
|---|---|---|
| 事实的**商户维度**（N1） | `SettlementFact` / `ReconciliationSettlementFact` / `PlatformFact` 均无 `merchantId`，settlement 无法在本地把事实归属到商户；修复需改跨服务契约（Constitution §8.4） | ADR-0023 记录，另立契约变更议题 |
| 负净额是否拒绝建批 | 当前 `compute` 允许负净额；本 Feature 保持允许，仅记 `settlement.negative_net` 且不记账 | ADR-0022 相关 `[待定]` |
| 建批后调整项的撤销/冲正流程 | MVP 拒绝建批后登记；反向冲正记新周期还是走人工流程未定 | ADR-0022 |
| settlement → ledger 记账是否本 Feature 落地 | Roadmap Phase 7「不实现 Ledger」vs Constitution §II.3 | ADR-0023 |
