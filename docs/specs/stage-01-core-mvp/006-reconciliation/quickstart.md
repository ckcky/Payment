# Quickstart: Reconciliation 对账（本地验证指南）

**Feature**: `006-reconciliation` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> 实现阶段完成后按本指南本地验证。当前（2026-08-29）为**文档先行**阶段，**实现未开始**，步骤待实现后执行。

## 0. 前置

- MySQL 8.0 就绪：`reconciliation` Schema 由 `deployment/schema/07-reconciliation-schema.sql` 建库建表（含本 Feature 新增的 `statement_source` / `closed_at` / `closed_by` 列）。
- 本地启动：`payment-service`(8084) / `refund-service`(8085) / `reconciliation-service`(8088) / `settlement-service`(8089，可选)。
- `reconciliation-service/src/main/resources/application.yml` 新增：`reconciliation.statement.fixture-dir`、`reconciliation.statement.default-file`、`services.payment.connect-timeout-ms` / `read-timeout-ms`（refund 同）。
- 账单 fixture：`fixtures/channel-statements/` 下准备 `2026-08.csv`、`2026-09.csv`（内容不同）与默认 `sample.csv`。

## 1. 单元 / 集成测试

```bash
# 对账服务（生命周期、账单按周期、重试、指标与审计）
./mvnw -pl reconciliation-service test

# 下游影响（结算资格依赖 unresolvedDifferenceCount）
./mvnw -pl settlement-service test

# 全量
./mvnw verify
```

## 2. 手动 e2e（开发联调）

### 2.1 按周期对账（缺口 G2）

1. 准备两份不同账单：
   ```csv
   # fixtures/channel-statements/2026-08.csv
   reference,amountMinor,currencyCode,status
   mock-ref-1,1000,CNY,SUCCEEDED
   channel-extra-8,888,CNY,SUCCEEDED

   # fixtures/channel-statements/2026-09.csv
   reference,amountMinor,currencyCode,status
   mock-ref-2,2000,CNY,SUCCEEDED
   mock-ref-1,1500,CNY,SUCCEEDED   # 金额与平台不一致 → AMOUNT_MISMATCH
   ```
2. `POST /internal/reconciliation/batches {"period":"2026-08"}` → 期望 `HAS_DIFFERENCE`，`statementSource.locator` 含 `2026-08.csv`、`fallbackUsed=false`。
3. `POST /internal/reconciliation/batches {"period":"2026-09"}` → 期望差异集合**与 2026-08 不同**，且含一条 `AMOUNT_MISMATCH`（`mock-ref-1`）。
4. `POST /internal/reconciliation/batches {"period":"2026-08"}`（重复）→ 期望返回**同一 `batchId`**，`reconciliation.run` 不重复计数。
5. `POST /internal/reconciliation/batches {"period":"2026-10"}`（无专属 fixture）→ 期望回退 `sample.csv`，`statementSource.fallbackUsed=true` 且 `reconciliation.statement_fallback` 递增、WARN 日志含 period。

### 2.2 差异查询与金额口径

```bash
GET /internal/reconciliation/batches/{id}/differences
# 期望：每条含 reference / type / platformAmountMinor / channelAmountMinor / resolutionStatus / resolutionNote

GET /internal/reconciliation/batches/{id}
# 期望：额外含 unresolvedDifferenceCount（= 未处理条数）、statementSource、closedAt

GET /internal/reconciliation/settlement-summary?period=2026-08
# 期望：facts 只含一致匹配；unresolvedDifferenceCount 与批次响应同源同值
```

### 2.3 差异处理 → 处理中 → 关闭（缺口 G1）

1. 取一个含 2 条差异的批次（状态 `HAS_DIFFERENCE`）。
2. `POST /internal/reconciliation/batches/{id}/differences/resolve {"reference":"...","resolutionNote":"渠道补单，已人工核对","operator":"ops-1"}`
   → 期望差异 `resolutionStatus=RESOLVED`（含 `resolvedBy`/`resolvedAt`），**批次状态由 `HAS_DIFFERENCE` 变为 `PROCESSING`**。
3. 立即 `POST /internal/reconciliation/batches/{id}/close` → 期望**被拒**（`UNRESOLVED_DIFFERENCES`，仍有 1 条未处理）。
4. 处理第 2 条差异（非空 `resolutionNote`）→ 期望批次仍在 `PROCESSING`（幂等）。
5. `POST /internal/reconciliation/batches/{id}/close` → 期望 `CLOSED`，`closed_at`/`closed_by` 落库。
6. 再次 `POST .../close` → 期望**幂等吸收**（仍 `CLOSED`，不报错）。
7. 对 `CLOSED` 批次发起 `resolve` → 期望被拒（`STATE_TRANSITION_VIOLATION`）。
8. 空 `resolutionNote` 的 `resolve` → 期望被拒（`INVALID_ARGUMENT`），`resolutionStatus` 不变。
9. `GET /internal/reconciliation/settlement-summary?period=...` → 期望 `unresolvedDifferenceCount=0`（`SettlementEligibility` 可结算）。

### 2.4 事实读取弹性（缺口 G3）

1. 停掉 `payment-service`（或让其 `confirmed-facts` 注入 5s 延迟）。
2. `POST /internal/reconciliation/batches {"period":"2026-11"}` → 期望在 read 超时（3s）内失败，响应 `INTERNAL_ERROR`，且 **该周期无批次落库**（`GET /internal/reconciliation/batches/{id}` 或库表查询为空）。
3. 恢复服务后重跑同周期 → 期望**成功**建批（未落库 ⇒ 可安全重跑）。
4. 注入「首次 500、第二次 200」→ 期望重试后成功（总耗时约 +1s）。
5. 持续失败 → 期望 `reconciliation.fact_read_failed{target=payment}` 递增，日志含 `traceId` 与 period。

### 2.5 指标与审计（N2 / N4）

- `/actuator/prometheus` 断言：
  - `reconciliation.run` / `reconciliation.difference{type=...}` / `reconciliation.difference_amount_minor`
  - `reconciliation.difference_resolved` / `reconciliation.batch_closed`
  - `reconciliation.statement_fallback` / `reconciliation.fact_read_failed{target=...}`
- 日志断言：差异处理与批次关闭各一条 `FINANCIAL_AUDIT`，含 `traceId`、`period`、`batchId`、`reference`、`fromStatus`/`toStatus`、`operator`、`resolutionNote`；无敏感字段。

### 2.6 原始事实零回写（回归硬约束）

对账与差异处理前后，分别抓取 `GET /internal/payments/confirmed-facts` 与 `GET /internal/refunds/confirmed-facts` 的快照并比对 → 期望**完全一致**（SC-005 / INV-6）。

## 3. 验收对照

逐项勾选 `acceptance.md`；重点确认 SC-001~SC-008 与 FR-001~FR-021。

## 4. 常见排查

- **两个周期结果相同**：检查是否命中了回退（`statementSource.fallbackUsed`），或 `reconciliation.statement.fixture-dir` 配置与实际 fixture 路径不一致（classpath 相对路径，无前导 `/`）。
- **批次状态停在 `HAS_DIFFERENCE`**：确认 `resolveDifference` 已调用 `batch.beginProcessing()`（T025）；注意 `ReconciliationApplicationService.java:104-115` 是唯一改动点。
- **关闭被拒但差异已全部处理**：检查 `unresolvedCount()` 与 `Difference.isResolved()` 的口径（`RESOLVED` 常量比较，`Difference.java:17/62`），以及 JSON 反序列化是否恢复了 `resolutionStatus`。
- **关闭报 `STATE_TRANSITION_VIOLATION`**：确认当前状态是 `CONSISTENT`/`PROCESSING`；`HAS_DIFFERENCE` 必须先处理至少一条差异。
- **超时重试未生效**：确认 `Retryer`/`Request.Options` 是**局部**绑定到 facts 客户端（非全局 Bean），且 `application.yml` 的 `connect-timeout-ms`/`read-timeout-ms` 已绑定。
- **失败却落了批次**：确认事实读取在 `insertNew` 之前（`:66-75`），且异常未被 catch 吞掉。
- **审计无输出**：确认 `StructuredAuditLogger` Bean 已注入（对账服务此前未使用过审计组件）。
- **金额出现小数/科学计数**：检查是否误用 `double`/`float` 或 `Long` 转 `Double`；差异金额应用 `Math.abs(long)`。
