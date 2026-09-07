# e2e-tests — 全链路自动化测试（spec 022 / ADR-0069）

黑盒测试模块：**不依赖任何业务模块**，只通过服务 HTTP API（JDK HttpClient）与 MySQL JDBC
（多 schema 探针）断言，测的是真实部署形态。承载四件事：**退款功能正常 / 超退能拦截 /
对账准确 / 单号记对**。

## 执行门禁（默认跳过）

- 根 reactor `./mvnw verify`（PR 快跑）**不执行**本模块（`skipTests=true`）——
  E2E 依赖 live 栈，进 PR 会假红（D4）；
- 显式激活：`-De2e.env=local`（本地已起栈）或 `-De2e.env=ci`（CI，nightly workflow）。

## 快速开始（本地）

```bash
# 1. 起栈（10 服务 + docker compose 的 MySQL/Redis/Nacos）
docker compose -f deployment/docker-compose.yml up -d
deployment/run-payment-services.sh

# 2. 跑全量 E2E（栈就绪检查 → 执行 → 报告指引）
bash deployment/e2e-tests/run.sh

# 跑单条
bash deployment/e2e-tests/run.sh -Dtest=RefundChainE2ETest#partialRefundChainKeepsAllStoresConsistent
```

报告：`target/surefire-reports/`；失败诊断 dump：`target/e2e-dump/<case>/`
（响应体 / trace 快照 / 相关表 SELECT * / invariants.log，FR-007）。

## 模块结构

| 层 | 类 | 职责 |
|---|---|---|
| support | `Env` / `Api` / `Db` / `Await` / `Trace` / `Dump` / `Invariants` / `E2eBase` | 环境配置 / HTTP 黑盒 / 多 schema JDBC / Awaitility 轮询（禁 Thread.sleep）/ trace 快照 / 失败落盘 / 断言原语库 / 基类（数据隔离 + 造单助手） |
| refund | `RefundChainE2ETest` / `OverRefundGuardE2ETest` | P0：部分/全额退款主链；单次超付 / 累计超退 / 并发不超付 |
| recon | `ReconciliationAccuracyE2ETest` / `ChannelStatementDiffE2ETest` / `SettlementGateE2ETest` | P0：四核对 CLEAN + FAULT 注入矩阵（DB 直改，用后即还原）；CSV 账实差异注入；结算门禁 |
| numbering | `BusinessNoChainE2ETest` | P0：单号链前缀 / 双号互记 / 跨库无孤儿 |
| reliability | `IdempotencyAndCallbackE2ETest` | P1：幂等重放 / 回调重复 / 回调丢失→resolve / 乱序不回退 |
| inventory | `SeckillRestockE2ETest` | P1：秒杀回补（需配 `e2e.seckill.sku-id`，未配置优雅跳过） |
| contract | `InternalApiSnapshotTest` | L3：API schema 快照（字段集合 + 类型），基线 `src/test/resources/api-snapshots/` |

## 断言原语（Invariants）

`refundNotExceedPaid` / `businessNoChain` / `ledgerBalanced` / `orderStatus` /
`entitlementRevoked` / `fulfillmentTerminated` / `stockConserved` / `idempotentReplay` /
`noOrphanRows`——失败信息必含期望值 / 实际值 / 业务单号 / 库表（NFR-003）。

## 确定性故障注入（T429 / D7）

- **请求级**（MockChannelAdapter）：金额尾数 `11`=渠道超时、`12`=无结论（回调丢失）、
  `15`=业务拒绝；基线 `PAYMENT_MOCK_SCENARIO` 保留。E2E 经
  `POST /products`+`/skus`+`/internal/stock/seed` 造指定价格 SKU 控制金额。
- **对账差异**：审计 FAULT 走 DB 直改（备份→注入→检出→还原，闭环可验证）；
  渠道账单差异经 `reconciliation.statement-dir-override`（`/tmp/e2e-channel-statements`）
  运行时落盘 `{period}.csv` 注入。
- **回调重复/乱序**：E2E 直发 `channel-callback` 端点（ADR-0025 验签占位放行）。

## 快照基线更新（契约有意变更时）

```bash
./mvnw -pl deployment/e2e-tests test -De2e.env=local -De2e.update-snapshots=true \
  -Dtest=InternalApiSnapshotTest
```

## CI

- PR：不跑 E2E（只跑 L3 快照 + ArchUnit 的部分在本模块默认跳过，见 `.github/workflows/verify.yml`）；
- Nightly：`.github/workflows/e2e.yml`——起 MySQL/Redis/Nacos → 起 9 服务 →
  `-De2e.env=ci` 全量 → 上传 surefire + dump 产物。release 前手动
  `workflow_dispatch` 强制过一遍（SC-006）。

## flaky 策略（T432）

用例**不自动重试**；新增用例连续 2 次 nightly 出现非确定性结果 → `@Disabled("flaky: <issue>")`
降级并登记待办，修复后恢复。收敛类等待一律走 `Await`（超时 15s/20s 可配），禁 `Thread.sleep`。
