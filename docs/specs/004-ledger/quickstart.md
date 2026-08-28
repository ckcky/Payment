# Quickstart: Ledger 资金账本（验证指南）

**Feature**: `004-ledger` | **Date**: 2026-08-28 | **Plan**: [plan.md](plan.md)

> 实现阶段完成后，按本指南本地验证。当前（2026-08-28）为文档先行阶段，步骤待实现后执行。

## 0. 前置

- MySQL 8.0 就绪，`ledger` Schema 由 `deployment/schema/09-ledger-schema.sql` 建库建表（接入 Flyway/Compose）。
- `ledger-service`（端口 8090）与调用方（payment/refund/settlement）同本地启动。

## 1. 单元 / 集成测试

```bash
# 账本服务单测 + Testcontainers 集成测试
./mvnw -pl ledger-service test

# 支付服务（含 LedgerPostingGateway 记账触发）测试
./mvnw -pl payment-service test

# 全量
./mvnw verify
```

## 2. 手动 e2e（开发联调）

1. 启动 `ledger-service` 与 `payment-service`。
2. 发起一笔支付并使其 `SUCCEEDED`（Mock Channel 成功）。
3. 断言账本：
   ```bash
   GET /internal/ledger/postings?sourceType=PAYMENT&sourceId=<paymentId>
   # 期望：1 条 Posting，其下 3 条 LedgerEntry 借贷平衡
   ```
4. 平衡性校验：
   ```bash
   GET /internal/ledger/balance-check
   # 期望：{ "balanced": true, "differenceMinor": 0 }
   ```
5. 重复触发同一支付记账 → 仍只有 1 条 Posting（幂等）。
6. 退款一笔 → 断言冲正 Posting 使「商户应付」回落，全局仍平衡。
7. 结算一笔 → 断言「应付→已结」Posting。

## 3. 验收对照

逐项勾选 `acceptance.md`；重点确认 SC-001~SC-005 与 FR-001~FR-011。

## 4. 常见排查

- **不平衡被拒**：检查 `PostingRequest.entries` 的 DEBIT/CREDIT 合计是否相等（同币种）。
- **幂等返回首次**：确认 `idempotencyKey` 与首次一致（格式 `PAYMENT:<...>`）。
- **记账失败未回滚支付**：确认调用方 `LedgerPostingGateway` 在 catch 中仅记录 `ledger.posting_failed` + 兜底，未抛异常回滚支付成功（ADR-0009）。
