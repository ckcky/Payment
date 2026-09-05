# Quickstart: Refund 退款（本地验证指南）

**Feature**: `005-refund` | **Date**: 2026-08-29 | **Plan**: [plan.md](plan.md)

> **状态（2026-08-31）：实现已完成并验收**，本指南已按最终裁决校准。
>
> ⛔ **ADR-0016「部分退款」裁决不做**：原 §2.2「部分退款」步骤**已删除**（改为「累计额度与防超退」），§2.5 的记账金额改为 `amountMinor`。
> ⚠️ 本机 `./mvnw` 不可用，构建请用本机 Maven（见 §1）。

## 0. 前置

- MySQL 8.0 就绪：`refund` Schema 由 `deployment/schema/06-refund-schema.sql` 建库建表（含本 Feature 新增的 `refund_post_process_attempts` 表）。
  ⛔ **无** `refunded_amount_minor` 列（ADR-0016 已回退）；若已部署环境曾加过该列，需手工执行
  `ALTER TABLE `refund`.`refunds` DROP COLUMN `refunded_amount_minor`;`
- 本地启动：`payment-service`(8084) / `refund-service`(8085) / `fulfillment-service`(8086) / `entitlement-service`(8087) / `ledger-service`(8090)。
- `refund-service` 的 `application.yml` 已含 `services.fulfillment.url`、`services.ledger.url`（T034 ✅）。

## 1. 单元 / 集成测试

> ⚠️ 本机 `./mvnw` 不可用（wrapper 异常），统一使用本机 Maven 并加 `-o`（离线）：
> `& 'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o <goals>`

```bash
# 退款服务（全额退款、后处理编排、记账、收敛防御）
& 'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o -pl refund-service test

# 履约服务（新增 on-refund 端点）
& 'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o -pl fulfillment-service test

# 受影响链路（支付侧退款尝试；账本侧记账）
& 'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o -pl payment-service,ledger-service test

# 全量（15 个 reactor 条目；-fae 防止 reactor 中止掩盖后续模块失败）
& 'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o clean verify -fae
```

## 2. 手动 e2e（开发联调）

### 2.1 全额退款（唯一受支持的退款形态）

1. 构造一笔 `SUCCEEDED` 支付（金额 1000）。
2. `POST /internal/refunds`（幂等键 `rf-full-1`，amount=1000）→ 期望 `SUCCEEDED`（响应**无** `refundedAmountMinor` 字段）。
3. 重复提交同一幂等键 → 期望返回首次结果，`refund.duplicate` 递增，**无**第二次渠道尝试。

### 2.2 累计额度与防超退（H1）

1. 第一笔 `POST /internal/refunds`（幂等键 `rf-a`，amount=300）→ `SUCCEEDED`，累计 300。
2. 令 Mock Channel 返回 `UNKNOWN` 后发第二笔（幂等键 `rf-b`，amount=400）→ 落 `UNKNOWN`，但**在途按申请额占用**，累计 700。
3. 第三笔（幂等键 `rf-c`，amount=400）→ 累计将达 1100 > 1000，期望 **`REJECTED`**，原因含 `exceeds refundable`，且**未**发起渠道尝试（payment-service 日志无 `refund-attempt`）。

> 自动化等价用例：`RefundApplicationServiceTest#cumulativeCountsRequestedAmountForBothTerminalAndInTransit`。

### ~~2.2b 部分退款（缺口 G1）~~ ⛔ 不做

> **ADR-0016 已裁决「部分退款不做」**，原场景（渠道退 300 → `PARTIALLY_SUCCEEDED` + `refundedAmountMinor=300`）**不适用**。
> 渠道仅回三态（`SUCCEEDED` / `FAILED` / `UNKNOWN`），不回传金额，成功即视为全额。

### 2.3 后处理编排（缺口 G2）

1. 令 fulfillment/entitlement 的 `on-refund` **均抛异常**（停服务或注入故障）。
2. 触发一笔退款成功 → 期望退款仍为 `SUCCEEDED`（不回滚）。
3. 查询后处理尝试记录 → 期望 FULFILLMENT / ENTITLEMENT 各一条 `FAILED`（含失败原因）。
4. 恢复两个服务后按 `refundNo` 重放 → 期望各自成功，且不重复撤销/吊销（下游幂等）。
5. 对已 `DELIVERED` 的履约触发退款 → 期望 fulfillment 返回 `SKIPPED`/`REJECTED`，**不算**后处理失败。

### 2.4 UNKNOWN 与收敛（缺口 G3）

1. 令 Mock Channel 返回 `UNKNOWN` → 退款落 `UNKNOWN`，**无**后处理、**无**记账。
2. ⚠️ 对仍处于 `REQUESTED` 的退款调 `POST /internal/refunds/{id}/resolve` → 当前**不被显式拒绝**：
   状态机静默吸收（`transitionTo` 返回 `false`，返回当前状态）。与 spec 原定 `STATE_TRANSITION_VIOLATION` 存在**已知偏差**（tasks T027）。
3. 对 `UNKNOWN` 退款重复调用 `resolve {"status":"SUCCEEDED"}` 三次 → 期望只收敛一次，后处理与记账各只发生一次。

### 2.5 记账（承接 004-ledger US2）

```bash
# 按幂等键回查退款冲正 Posting
GET /internal/ledger/postings?idempotencyKey=REFUND:rf-full-1
# 期望：1 条 Posting，sourceType=REFUND，借贷平衡，金额 = 1000（= amountMinor，成功退款恒为全额）

# 按来源追溯
GET /internal/ledger/entries?sourceType=REFUND&sourceId=<refundNo>

# 全局平衡性校验
GET /internal/ledger/balance
# 期望：{ "balanced": true, ... }
```

停掉 `ledger-service` 后触发退款 → 期望退款仍成功，记账尝试记为 `FAILED`（`ledger.posting_failed` 递增），不回滚。

### 2.6 指标与审计

- `/actuator/prometheus` 断言：`refund.succeeded` / `refund.unknown` / `refund.failed` / `refund.post_process_failed` 计数符合上述操作。
  ⛔ 无 `refund.partially_succeeded`（ADR-0016 不做）。
- 日志断言：`FINANCIAL_AUDIT` 每条含 `traceId`、`idempotencyKey`、`amountMinor`、`fromStatus`、`toStatus`。

## 3. 验收对照

逐项勾选 `acceptance.md`；重点确认 SC-001~SC-006 与 FR-001~FR-017。

## 4. 常见排查

- **（已不适用）部分退款却落 SUCCEEDED**：ADR-0016 裁决不做，渠道只回三态，成功即全额——若确实发生部分退回，走 `UNKNOWN` + 对账收敛。
- **累计额度算错**：确认累计口径是「终态与在途**一律按申请额** `amountMinor`」（data-model §3；实现在 `RefundApplicationService`，**非** `RefundPolicy`），且 `refund_intake_locks` 悲观锁生效。
- **后处理失败看不到记录**：确认 `RefundApplicationService` 已改用 `RefundPostProcessOrchestrator`，旧的 `catch (RuntimeException ignored)` 已移除。
- **fulfillment 返回 REJECTED**：检查履约状态——`Fulfillment.cancel()` 仅支持 `PENDING → CANCELLED`，已交付属预期 `SKIPPED`。
- **记账不平衡被拒**：检查 DEBIT/CREDIT 合计是否相等、金额是否取 `refund.getAmountMinor()`；`accountId`（非 `accountCode`）是否为账本预置科目的实际 ID。
- **记账失败却回滚了退款**：确认网关 catch 中只记录兜底、未抛异常（ADR-0018）。
