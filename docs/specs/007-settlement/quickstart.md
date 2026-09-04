# Quickstart: Settlement 结算（本地验证指南）

**Feature**: `007-settlement` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> 实现阶段完成后按本指南本地验证。当前（2026-08-29）为**文档先行**阶段，**实现未开始**，步骤待实现后执行。

## 0. 前置

- MySQL 8.0 就绪：`settlement` Schema 由 `deployment/schema/08-settlement-schema.sql` 建库建表（含本 Feature 新增的 `settlement_adjustments` 表与 `settlement_batches.fact_count` / `source_period` 两列）。
- 本地启动：`merchant-service`(8081) / `reconciliation-service`(8088) / `settlement-service`(8089) / `ledger-service`(8090，记账路径需要)。
- `settlement-service/src/main/resources/application.yml` 新增：`services.ledger.url`（默认 `http://localhost:8090`）、`services.merchant.connect-timeout-ms` / `read-timeout-ms`、`services.reconciliation.*`（reconciliation/ledger 同）。
- 数据准备：至少一个 `ACTIVE` 且 `settlementEligible=true` 的商户；目标周期已在 reconciliation 跑过对账（`settlement-summary` 可读，未跑返回 `NOT_FOUND`）。

## 1. 单元 / 集成测试

```bash
# 结算服务（调整项净额、登记幂等与拒绝、闸门四类拒绝、close 幂等、记账幂等与失败不回滚）
./mvnw -pl settlement-service -am test

# 全量
./mvnw verify
```

## 2. 手动 e2e（开发联调）

### 2.1 调整项登记与净额（缺口 G1）

1. 登记补差与扣款：
   ```bash
   POST /internal/settlements/adjustments
   {"merchantId":"M1","period":"2026-08","direction":"CREDIT","amountMinor":500,
    "currencyCode":"CNY","reason":"平台赔付补差","operator":"ops-1","idempotencyKey":"adj-1"}

   POST /internal/settlements/adjustments
   {"merchantId":"M1","period":"2026-08","direction":"DEBIT","amountMinor":300,
    "currencyCode":"CNY","reason":"客诉扣款","operator":"ops-1","idempotencyKey":"adj-2"}
   ```
   期望：均落库为 `ACTIVE`，各写一条 `FINANCIAL_AUDIT`（含 `traceId`/`operator`/`reason`/`direction`/`amountMinor`），`settlement.adjustment_registered{direction}` 各 +1。
2. 重复提交 `adj-1`（同参）→ 期望返回首次调整项，不产生第二条。
3. 提交 `adj-1` 但金额改为 `600` → 期望被拒（`DUPLICATE`），首次登记金额不变。
4. `reason` 为空 → 期望被拒（`INVALID_ARGUMENT`），不落库。
5. 建批：
   ```bash
   POST /internal/settlements/batches
   {"merchantId":"M1","period":"2026-08","currencyCode":"CNY","idempotencyKey":"settle-M1-2026-08"}
   ```
   期望：`adjustmentMinor = +200`、`netMinor = income − refund + 200`、`factCount` = 参与计算的事实条数，明细含 2 条 `ADJUSTMENT`（`+500` / `-300`），带符号求和 = `+200`；批次状态为 `UNKNOWN`（模拟执行，无真实出款）。
6. 批次已存在后再登记 `adj-3` → 期望被拒（`STATE_TRANSITION_VIOLATION`），`settlement.adjustment_rejected{reason=batch_exists}` 递增，批次净额不变。

### 2.2 已确认事实闸门（缺口 G2 / G3）

构造一份含非法事实的 `settlement-summary`（可用测试桩或临时改 fixture），分别建批：

| 非法事实 | 期望 |
|---|---|
| `type="FEE"` | 拒绝（`INVALID_ARGUMENT`），`gate_rejected{reason=unknown_fact_type}` |
| `currencyCode="USD"` | 拒绝（`AMOUNT_INVARIANT_VIOLATION`），`gate_rejected{reason=currency_mismatch}` |
| `amountMinor=-100` | 拒绝（`AMOUNT_INVARIANT_VIOLATION`），`gate_rejected{reason=negative_amount}` |
| `summary.period="2026-07"`（请求 `2026-08`） | 拒绝（`INVALID_ARGUMENT`），`gate_rejected{reason=period_mismatch}` |

- 每种情况都断言：`settlement_batches` 中该商户周期**无记录**（不落半成品）、WARN 日志含 `traceId`/`merchantId`/`period`。
- 对未对账过的周期建批 → 期望 `NOT_FOUND`（不是 500）。

### 2.3 查询、收敛、关闭与记账（缺口 N3 / N6 / G4）

1. `GET /internal/settlements/batches?merchantId=M1&period=2026-08` → 期望命中 2.1 建出的批次，返回完整金额与 `UNKNOWN` 状态；不存在的组合返回 `NOT_FOUND`。
2. 收敛（携带人工依据）：
   ```bash
   POST /internal/settlements/batches/{id}/resolve
   {"status":"SUCCEEDED","operator":"ops-1","reason":"渠道对账单已确认，人工放款"}
   ```
   期望：批次进 `SUCCEEDED`，审计含 `fromStatus`/`toStatus`/`operator`/`reason`/`traceId`；`operator`/`reason` 为空则被拒（`INVALID_ARGUMENT`）。
3. 记账断言（ADR-0023 采纳时）：`GET /internal/ledger/postings?sourceType=SETTLEMENT` 或按 `SETTLEMENT:<batchId>` 回查 → 期望存在一条**平衡** Posting（DEBIT `MERCHANT_PAYABLE` / CREDIT `SETTLEMENT_PAYABLE`，金额 = `netMinor`）。
4. 重复收敛为 `SUCCEEDED` → 期望幂等吸收，**无第二条 Posting**。
5. 停掉 `ledger-service` 后收敛另一批次为 `SUCCEEDED` → 期望批次**仍为 `SUCCEEDED`**（不回滚），`ledger.posting_failed{module=settlement}` 递增并留下待记账日志。
6. `netMinor <= 0` 的批次收敛为 `SUCCEEDED` → 期望**不发起**记账，`settlement.negative_net` 递增。
7. 关闭：
   ```bash
   POST /internal/settlements/batches/{id}/close
   {"operator":"ops-1"}
   ```
   期望：`SUCCEEDED`/`FAILED` → `CLOSED`；重复关闭**幂等吸收**；`UNKNOWN`/`EXECUTING` 关闭被拒（`STATE_TRANSITION_VIOLATION`）。

### 2.4 出站弹性（缺口 N4）

1. 让 `merchant-service` 的 `GET /merchants/{id}` 注入 5s 延迟 → 期望建批在 read 超时（3s）内失败（`INTERNAL_ERROR`），**不落批次**。
2. 注入「首次 500、第二次 200」→ 期望重试后成功（只读 GET 最多 3 次、退避 1s/2s/4s）。
3. 对记账 POST 注入 500 → 期望**不重试**（写操作禁重试），直接记 `ledger.posting_failed`。

### 2.5 指标与审计

- `/actuator/prometheus` 断言：
  - `settlement.batch_initiated` / `settlement.unknown` / `settlement.failed`（既有）
  - `settlement.adjustment_registered{direction}` / `settlement.adjustment_rejected{reason}`
  - `settlement.gate_rejected{reason}` / `settlement.negative_net` / `settlement.closed`
  - `ledger.posting_succeeded` / `ledger.posting_failed`（`module=settlement`）
- 日志断言：登记调整、建批、UNKNOWN、失败、关闭、记账各一条 `FINANCIAL_AUDIT`，含 `traceId`、`operator`、`reason`、前后状态；无敏感字段、不含事实明细全文。

### 2.6 硬约束回归

- 代码级检视：全仓库搜索银行/渠道出款调用 → **0 条**；金额路径 `float`/`double` → **0 处**；settlement 侧跨服务 SQL → **0 处**。
- 对账前后抓取 `GET /internal/reconciliation/settlement-summary?period=...` 快照比对 → 期望**完全一致**（零回写，INV-15）。

## 3. 验收对照

逐项勾选 `acceptance.md`；重点确认 SC-001~SC-010 与 FR-001~FR-024。

## 4. 常见排查

- **调整项没算进净额**：确认 `createBatch` 走的是 `findActiveByMerchantAndPeriod` 而非硬编码 `0`（`SettlementApplicationService.java:80` 是原始缺口点）。
- **建批报 `AMOUNT_INVARIANT_VIOLATION`**：多半是 `adjustmentMinor` 与 `ADJUSTMENT` 明细带符号求和不符；注意 `DEBIT` 明细落库为**负数**，`amountMinor` 实体字段恒正。
- **闸门全部放行**：确认 `ConfirmedFactGate` 在 `createBatch` 里被调用，且 `SettlementFact.type()` 的取值与 `{PAYMENT, REFUND}` 完全一致（大小写敏感）。
- **close 报 `STATE_TRANSITION_VIOLATION`**：确认批次处于 `SUCCEEDED`/`FAILED`；`UNKNOWN` 必须先 `resolve`。
- **记账产生了第二条 Posting**：确认幂等键固定为 `SETTLEMENT:<batchId>` 且先回查再提交。
- **超时/重试没生效**：确认 `FeignResilienceConfig` 是**局部**绑定到各 `@FeignClient(configuration=...)`，且未被 `@ComponentScan` 扫成全局 Bean。
- **事实串到别的商户**：这是已知缺口 **N1**（事实无 `merchantId`），本 Feature 不修复；请用单商户数据验证，并参见 ADR-0023。
