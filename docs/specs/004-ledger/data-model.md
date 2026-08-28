# Data Model: Ledger 资金账本

**Feature**: `004-ledger` | **Date**: 2026-08-28 | **Plan**: [plan.md](plan.md)

> 本文件定义 Ledger 领域实体与字段设计。金额表示（Money VO vs long 分）与科目表设计见 ADR-0010 / ADR-0008。

## 1. 实体关系（MVP）

```text
Account (1) ──── (N) LedgerEntry (N) ──── (1) Posting (1) ──── 业务来源 (PAYMENT/REFUND/SETTLEMENT + source_id)
```

- `Account`：科目，系统预置、可变但 MVP 不动态新建。
- `Posting`：一次业务事件的记账容器，聚合根；持有幂等键与来源。
- `LedgerEntry`：单条借贷记录，不可变（append-only）。

## 2. Account（科目）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 科目 ID |
| code | VARCHAR(32) NOT NULL UNIQUE | 科目编码，如 `CUSTOMER_CASH` / `MERCHANT_PAYABLE` / `PLATFORM_FEE_REVENUE` / `SETTLEMENT_PAYABLE` |
| name | VARCHAR(64) | 科目名 |
| type | VARCHAR(16) NOT NULL | ASSET / LIABILITY / REVENUE / EXPENSE / EQUITY |
| currency | VARCHAR(8) NOT NULL | 币种（MVP 仅 CNY） |
| balance_minor | BIGINT NOT NULL DEFAULT 0 | 派生余额（可选缓存；以分录聚合为准，MVP 可仅查询聚合） |

**MVP 预置科目（Chart of Accounts，具体见 ADR-0008）**：

| code | type | 含义 |
|---|---|---|
| `CUSTOMER_CASH` | ASSET | 客户/平台持有的已收资金 |
| `MERCHANT_PAYABLE` | LIABILITY | 应付商户净额 |
| `PLATFORM_FEE_REVENUE` | REVENUE | 平台手续费收入 |
| `SETTLEMENT_PAYABLE` | LIABILITY | 已结算待出款（MVP 不出款） |

> 单币种（CNY）起步；多币种按 `currency` 维度隔离，清分属后续。

## 3. Posting（记账批次，聚合根）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | Posting ID |
| idempotency_key | VARCHAR(128) NOT NULL UNIQUE | 幂等键（业务提供，如 `PAYMENT:<paymentIdempotencyKey>`） |
| source_type | VARCHAR(16) NOT NULL | PAYMENT / REFUND / SETTLEMENT |
| source_id | VARCHAR(64) NOT NULL | 业务来源 ID（paymentId / refundId / batchId） |
| status | VARCHAR(16) NOT NULL | PENDING → POSTED（MVP 仅 POSTED） |
| currency | VARCHAR(8) NOT NULL | 记账币种 |
| created_at / created_by / version | — | 审计 + 乐观锁 |

**不变量**：`status == POSTED` 时，其下所有 `LedgerEntry` 满足 `sum(direction=DEBIT amount) == sum(direction=CREDIT amount)`（同币种）。

## 4. LedgerEntry（分录，不可变）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO_INCREMENT | 分录 ID |
| posting_id | BIGINT NOT NULL | 所属 Posting，普通索引 |
| account_id | BIGINT NOT NULL | 科目引用 |
| direction | VARCHAR(8) NOT NULL | DEBIT / CREDIT |
| amount_minor | BIGINT NOT NULL | 金额（最小货币单位，> 0） |
| currency | VARCHAR(8) NOT NULL | 币种 |
| entry_type | VARCHAR(32) NOT NULL | PAYMENT_CAPTURE / REFUND / SETTLEMENT / FEE |
| source_type | VARCHAR(16) NOT NULL | 来源类型（冗余便于追溯） |
| source_id | VARCHAR(64) NOT NULL | 来源 ID（冗余便于追溯） |
| created_at | DATETIME | 创建时间 |

**不可变性**：已提交分录 MUST NOT UPDATE/DELETE；更正只能新增反向分录（冲正），与业务退款同机制。

## 5. 关键业务映射（MVP，详见 ADR-0011）

**支付成功（金额 A，手续费 F，净额 N=A-F）**：
- DEBIT `CUSTOMER_CASH` A
- CREDIT `MERCHANT_PAYABLE` N
- CREDIT `PLATFORM_FEE_REVENUE` F
- 平衡：A = N + F ✅

**退款（金额 R）**：
- DEBIT `MERCHANT_PAYABLE` R
- CREDIT `CUSTOMER_CASH` R
- 平衡 ✅（与支付方向相反）

**结算（商户周期净额 S）**：
- DEBIT `MERCHANT_PAYABLE` S
- CREDIT `SETTLEMENT_PAYABLE` S
- 平衡 ✅

## 6. 幂等与重试/兜底（详见 ADR-0009）

- **幂等**：`postings.uk_postings_idempotency_key` 唯一约束；重复请求捕获 `DuplicateKeyException` 后回查返回首次 Posting（与 payment-service 同模式）。
- **重试/兜底**：调用方 `LedgerPostingGateway`（Feign，沿用 `ResilientFulfillmentGateway` 模式）对幂等记账 RPC 做有限退避重试；耗尽仍失败 → 记录 `ledger.posting_failed` 指标 + `FINANCIAL_AUDIT` 告警 + 进入「待记账」清单，由 reconciliation 对账补齐（不回滚业务事实）。

## 7. 索引策略

- `postings`：`uk_postings_idempotency_key`（幂等兜底）、`idx_postings_source`(source_type, source_id)（按来源回查）。
- `ledger_entries`：`idx_entries_posting`(posting_id)、`idx_entries_source`(source_type, source_id)（追溯）、`idx_entries_account`(account_id, currency)（余额聚合）。
