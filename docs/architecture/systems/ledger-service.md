# ledger-service 系统设计

**服务**：ledger-service（复式记账 / 资金单一事实源）
**端口**：8090 | **Schema**：`ledger`（`deployment/schema/09-ledger-schema.sql`）| **包根**：`com.payment.ledger`

**上游依赖**：payment-service（`PAYMENT` 来源记账）、payment-service（`REFUND` 来源冲正记账，Feature 015 起退款域并入 payment）、settlement-service（`SETTLEMENT` 来源记账）、reconciliation-service（读账本事实做对账）
**下游依赖**：无（自身持有 `ledger` 库，不反向依赖任何业务领域）

> 标注约定：无标记 = 已实现；`[目标]` = 建议值待确认；`[待定]` = 留待后续；`[Phase N 延后]` = 明确延后。

---

## 1. 设计目标与约束

### 1.1 职责边界（负责 / 不负责）

| 维度 | 说明 |
|---|---|
| **负责** | 复式记账（借贷平衡）、预置科目表、幂等吸收重复记账、不可变 append-only 分录、全局借贷平衡校验、按业务来源追溯、资金审计日志 |
| **不负责** | 支付/退款/结算的业务决策（归属各自服务）；账务展示与报表（本期不提供）；实际出款（SETTLEMENT_PAYABLE 仅记负债，MVP 不出款）；科目动态新建（MVP 固定 4 科目） |

### 1.2 硬约束（Constitution / ADR）

- **资金单一事实源**：所有资金变动 MUST 经账本（Constitution §II / ADR-0008），业务服务不得自记资金。
- **借贷平衡门禁**：`Posting` 聚合根构造期即校验同币种 `sum(DEBIT) == sum(CREDIT)`；不平衡直接抛 `LEDGER_UNBALANCED`，**不落任何分录**（数据质量门禁，非业务错误）。
- **分录不可变**：`LedgerEntry` 一经提交 MUST NOT UPDATE/DELETE；更正只能新增反向分录（冲正），与退款同机制。
- **幂等**：记账入口接受调用方幂等键，`uk_postings_idempotency_key` 唯一约束兜底，重复调用返回首次结果。
- **禁 2PC/XA**：记账 RPC 失败不回滚上游业务成功事实；失败仅记指标 + 告警，交由重试/对账兜底（ADR-0009 / ADR-0018）。
- **仅内部调用**：端点挂在 `/internal/ledger`，仅被其他服务经 Feign 调用，不面向公网（FR-005）。
- **金额铁律**：金额一律最小货币单位 `long`（`amountMinor`），不变量 `amountMinor > 0`。

### 1.3 技术指标

| 指标 | 现状 |
|---|---|
| 记账吞吐 / 延迟 | 随系统压测基线（见 `docs/adr/0018-performance-baseline.md` ADR-0058） |
| 多币种 | 当前仅 CNY；`currency` 维度已建模，按币种隔离借贷 |

---

## 2. 核心领域模型

### 2.1 聚合根 `Posting`（一次业务事件 = 一组平衡分录）

- 字段：`idempotencyKey`、`sourceType`(PAYMENT/REFUND/SETTLEMENT)、`sourceId`、`currency`、`entries`、`status`(PENDING/POSTED，MVP 仅 POSTED)。
- 不变量：
  - 至少 2 条分录（`entries.size() < 2` 即拒绝）。
  - 同币种 `sum(DEBIT) == sum(CREDIT)`；跨币种或币种与 `Posting.currency` 不一致直接抛 `LEDGER_UNBALANCED`。
  - 构造期即 `requireBalanced()`，保证**不平衡的分录永不入库存**。

### 2.2 值对象 `LedgerEntry`（不可变分录）

- 字段：`postingId`、`accountId`、`direction`(DEBIT/CREDIT)、`amountMinor`、`currency`、`entryType`(PAYMENT_CAPTURE/FEE/REFUND/SETTLEMENT)、`sourceType`、`sourceId`。
- 不变量：`amountMinor > 0`（否则抛 `AMOUNT_INVARIANT_VIOLATION`）。
- 持久化重建走 `rehydrate(...)`；业务层不可 UPDATE/DELETE，只能通过新增反向分录冲正。

### 2.3 预置科目表 `Account`（Chart of Accounts，ADR-0008）

MVP 为应用侧固定枚举，ID 与 `deployment/schema/09-ledger-schema.sql` 种子数据一一对应，不动态新建：

| ID | code | 类型 | 含义 |
|---|---|---|---|
| 1 | CUSTOMER_CASH | ASSET | 客户/平台持有的已收资金 |
| 2 | MERCHANT_PAYABLE | LIABILITY | 应付商户净额 |
| 3 | PLATFORM_FEE_REVENUE | REVENUE | 平台手续费收入 |
| 4 | SETTLEMENT_PAYABLE | LIABILITY | 已结算待出款（MVP 不出款） |

---

## 3. 记账应用服务与幂等/审计

`LedgerPostingService.post(idempotencyKey, sourceType, sourceId, currency, entries)`：

1. **回查优先**：`findByIdempotencyKey(...)`，命中即返回首次结果（幂等幂等回放）。
2. **聚合根校验**：未命中则构造 `Posting`（构造期拒绝不平衡）。
3. **落库 + 唯一约束兜底**：`save(...)` 撞 `uk_postings_idempotency_key`（`DuplicateKeyException`）时，**回查返回首次结果**，不重复入账，并记 `ledger.duplicate` 指标。
4. **资金审计**：成功后经 `StructuredAuditLogger` 写两条审计（`ledger.posted` 汇总 + `ledger.entries` 分录摘要，FR-011）。

> 关键取舍：幂等以「业务提供幂等键 + DB 唯一约束」双保险实现，而非分布式锁；并发/重启后的重复插入由唯一约束吸收，符合「先查后插 + 冲突回查」的轻量幂等范式。

---

## 4. 端点（`/internal/ledger`，仅内部 Feign 调用）

| 方法 | 路径 | 作用 | 说明 |
|---|---|---|---|
| POST | `/postings` | 记账（幂等） | `@Valid @RequestBody PostingRequest`；借贷不平衡由聚合根拒绝 |
| GET | `/postings` | 按幂等键回查 | `idempotencyKey` 参数；未命中抛 `IllegalArgumentException` |
| GET | `/balance` | 全局借贷平衡校验（FR-007） | 返回 `balanced` + 各币种差额 `diffByCurrency`（平衡时全 0） |
| GET | `/entries` | 按业务来源追溯分录（FR-008） | `sourceType` + `sourceId`；返回该来源全部分录 |

入参/出参契约见 `common/common-dto/.../rpc/PostingRequest.java` 与 `PostingResponse.java`（`EntryView` 含 `accountId/direction/amountMinor/currency/entryType`）。

---

## 5. 记账接入点（三来源分录模板）

各业务服务经各自的 `LedgerPostingGateway`（Feign 实现）同步 RPC 记账。**记账失败不回滚上游成功事实**，仅记 `ledger.posting_failed` 指标 + 告警，由重试/对账兜底。

### 5.1 payment-service → `PAYMENT:<支付幂等键>`（ADR-0009 / FR-006/FR-010）

支付**已确认成功**才记账（UNKNOWN/PROCESSING 不记账，Constitution §V.7）。
分录（借贷平衡 `A = N + F`）：

- `DEBIT  CUSTOMER_CASH(1)  amountMinor` （PAYMENT_CAPTURE）
- `CREDIT MERCHANT_PAYABLE(2)  netMinor = amountMinor - feeMinor` （PAYMENT_CAPTURE，仅当 `netMinor > 0`）
- `CREDIT PLATFORM_FEE_REVENUE(3)  feeMinor` （FEE，仅当 `feeMinor > 0`）

### 5.2 refund-service → `REFUND:<退款幂等键>`（ADR-0018）

退款**已确认成功**（SUCCEEDED / PARTIALLY_SUCCEEDED）才记账；金额 = 实际退款额，必须 `> 0`。
冲正分录（与支付成功反向）：

- `DEBIT  MERCHANT_PAYABLE(2)  amountMinor` （REFUND）
- `CREDIT CUSTOMER_CASH(1)  amountMinor` （REFUND）

### 5.3 settlement-service → `SETTLEMENT:<批次幂等键>`

结算批次确认时记账；金额 = 批次净额 `netMinor`。
分录：

- `DEBIT  MERCHANT_PAYABLE(2)  netMinor` （SETTLEMENT）
- `CREDIT SETTLEMENT_PAYABLE(4)  netMinor` （SETTLEMENT，MVP 不出款，仅记负债）

---

## 6. 持久化与 Schema（`09-ledger-schema.sql`）

Database-per-Service：账本服务自有 `ledger` 库（单机开发由 docker-compose 的 MySQL 8 实例承载，多库共实例、服务间不共享表）。

| 表 | 关键列 | 约束 |
|---|---|---|
| `accounts` | id, code, name, type, currency | `uk_accounts_code`；种子 4 预置科目 |
| `postings` | id, idempotency_key, source_type, source_id, status, currency, version | `uk_postings_idempotency_key`（幂等兜底）、`idx_postings_source` |
| `ledger_entries` | id, posting_id, account_id, direction, amount_minor, currency, entry_type, source_type, source_id | `idx_entries_posting/source/account` |

仓储边界 `LedgerRepository`（领域接口）由 `MybatisLedgerRepository` 实现，内存实现 `InMemoryLedgerRepository` 供测试；`BalanceChecker` 提供 `isBalanced()` / `byCurrency()` / `accountBalance()` / `entriesOfSource()`。

---

## 7. 与对账 / 运维的关系

- **对账依据（FR-007/SC-004）**：`GET /balance` 返回全局借贷差额，平衡时各币种均为 0；reconciliation-service 可据此校验「账务事实」自洽。
- **来源追溯（FR-008）**：`GET /entries` 按 `sourceType + sourceId` 回查某笔支付/退款/结算的全部分录，支撑审计与差错定位。
- **资金审计（FR-011）**：每笔成功记账经 `StructuredAuditLogger` 落审计，与 Constitution 资金审计要求对齐。

---

## 8. 关联决策与功能需求

- **ADR-0008**：账本作为资金单一事实源，预置固定科目表。
- **ADR-0009**：payment → ledger 同步 RPC，禁 2PC，记账失败不回滚支付事实。
- **ADR-0018**：refund → ledger 冲正记账，同取舍。
- **ADR-0054**（确认性）：回调与资金约束（含账本强一致要求）见 `docs/adr/0016-core-payment-correctness.md`。
- **功能需求标签（代码中标注）**：FR-001/002（Posting 聚合根与平衡门禁）、FR-003（分录不可变）、FR-004（幂等回查）、FR-005（仅内部端点）、FR-006/010（支付成功记账）、FR-007（全局平衡校验）、FR-008（来源追溯）、FR-011（资金审计）。
