# Quickstart: Refund 退款（本地验证指南）

**Feature**: `005-refund` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> 实现阶段完成后按本指南本地验证。当前（2026-08-29）为**文档先行**阶段，**实现未开始**，步骤待实现后执行。

## 0. 前置

- MySQL 8.0 就绪：`refund` Schema 由 `deployment/schema/06-refund-schema.sql` 建库建表（含本 Feature 新增的 `refunded_amount_minor` 列与 `refund_post_process_attempts` 表）。
- 本地启动：`payment-service`(8084) / `refund-service`(8085) / `fulfillment-service`(8086) / `entitlement-service`(8087) / `ledger-service`(8090)。
- `refund-service` 的 `application.yml` 需新增 `services.fulfillment.url`、`services.ledger.url`（T034）。

## 1. 单元 / 集成测试

```bash
# 退款服务（含部分退款、后处理编排、记账、收敛防御）
./mvnw -pl refund-service test

# 履约服务（新增 on-refund 端点）
./mvnw -pl fulfillment-service test

# 受影响链路（支付侧回传实际退款金额；账本侧记账）
./mvnw -pl payment-service,ledger-service test

# 全量
./mvnw verify
```

## 2. 手动 e2e（开发联调）

### 2.1 全额退款（回归，已实现路径）

1. 构造一笔 `SUCCEEDED` 支付（金额 1000）。
2. `POST /internal/refunds`（幂等键 `rf-full-1`，amount=1000）→ 期望 `SUCCEEDED`、`refundedAmountMinor=1000`。
3. 重复提交同一幂等键 → 期望返回首次结果，`refund.duplicate` 递增，**无**第二次渠道尝试。

### 2.2 部分退款（缺口 G1）

1. 令 Mock Channel 对该笔支付只退回 300。
2. `POST /internal/refunds`（幂等键 `rf-part-1`，amount=1000）→ 期望 `PARTIALLY_SUCCEEDED`、`refundedAmountMinor=300`。
3. `POST /internal/refunds`（幂等键 `rf-part-2`，amount=700）→ 期望 `SUCCEEDED`（累计 300+700=1000）。
4. `POST /internal/refunds`（幂等键 `rf-part-3`，amount=1）→ 期望 `REJECTED`，原因含 `exceeds refundable`，且**未**发起渠道尝试（查看 payment-service 日志无 `refund-attempt`）。

### 2.3 后处理编排（缺口 G2）

1. 令 fulfillment/entitlement 的 `on-refund` **均抛异常**（停服务或注入故障）。
2. 触发一笔退款成功 → 期望退款仍为 `SUCCEEDED`（不回滚）。
3. 查询后处理尝试记录 → 期望 FULFILLMENT / ENTITLEMENT 各一条 `FAILED`（含失败原因）。
4. 恢复两个服务后按 `refundId` 重放 → 期望各自成功，且不重复撤销/吊销（下游幂等）。
5. 对已 `DELIVERED` 的履约触发退款 → 期望 fulfillment 返回 `SKIPPED`/`REJECTED`，**不算**后处理失败。

### 2.4 UNKNOWN 与收敛（缺口 G3）

1. 令 Mock Channel 返回 `UNKNOWN` → 退款落 `UNKNOWN`，`refundedAmountMinor=0`，**无**后处理、**无**记账。
2. 对仍处于 `REQUESTED` 的退款调 `POST /internal/refunds/{id}/resolve` → 期望 `STATE_TRANSITION_VIOLATION` 且响应含当前状态。
3. 对 `UNKNOWN` 退款重复调用 `resolve {"status":"SUCCEEDED"}` 三次 → 期望只收敛一次，后处理与记账各只发生一次。

### 2.5 记账（承接 004-ledger US2）

```bash
# 按幂等键回查退款冲正 Posting
GET /internal/ledger/postings?idempotencyKey=REFUND:rf-part-1
# 期望：1 条 Posting，sourceType=REFUND，借贷平衡，金额 = 300（实际退款额，非 1000）

# 按来源追溯
GET /internal/ledger/entries?sourceType=REFUND&sourceId=<refundId>

# 全局平衡性校验
GET /internal/ledger/balance
# 期望：{ "balanced": true, ... }
```

停掉 `ledger-service` 后触发退款 → 期望退款仍成功，记账尝试记为 `FAILED`（`ledger.posting_failed` 递增），不回滚。

### 2.6 指标与审计

- `/actuator/prometheus` 断言：`refund.succeeded` / `refund.partially_succeeded` / `refund.unknown` / `refund.post_process_failed` 计数符合上述操作。
- 日志断言：`FINANCIAL_AUDIT` 每条含 `traceId`、`idempotencyKey`、`amountMinor`、`fromStatus`、`toStatus`。

## 3. 验收对照

逐项勾选 `acceptance.md`；重点确认 SC-001~SC-006 与 FR-001~FR-017。

## 4. 常见排查

- **部分退款却落 SUCCEEDED**：检查 payment-service 回传的 `refundedAmountMinor` 是否缺失；契约未升级时 refund 侧按 ADR-0016 默认策略处理（缺失 → 视为全额）。
- **累计额度算错**：确认 `RefundPolicy` 的口径已改为「终态计已确认额、在途计申请额」（data-model §3），且 `refund_intake_locks` 悲观锁生效。
- **后处理失败看不到记录**：确认 `RefundApplicationService` 已改用 `RefundPostProcessOrchestrator`，旧的 `catch (RuntimeException ignored)` 已移除。
- **fulfillment 返回 REJECTED**：检查履约状态——`Fulfillment.cancel()` 仅支持 `PENDING → CANCELLED`，已交付属预期 `SKIPPED`。
- **记账不平衡被拒**：检查 DEBIT/CREDIT 合计是否相等、金额是否取 `refundedAmountMinor` 而非 `amountMinor`；`accountId`（非 `accountCode`）是否为账本预置科目的实际 ID。
- **记账失败却回滚了退款**：确认网关 catch 中只记录兜底、未抛异常（ADR-0018）。
