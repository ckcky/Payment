# Tasks: 018-schema-normalization-item-fulfillment

> 承载目标：全项目表结构列序规范化 + payment_attempts 金额留痕 + 按 order_item 粒度履约 + demo 中文注释 + 门户主界面。
> **当前状态：✅ 全部完成（2026-09-07）**。T001~T016 全勾：单测全绿 + `mvn clean verify -fae` 15 模块 BUILD SUCCESS + live 冒烟通过。
> 每个任务完成后跑对应模块测试门禁，最后统一 `mvn -o clean verify -fae`。

## 批次 A — 文档与决策（已完成）

- [x] **T001** 编写 spec 018 四件套：spec.md（US1~US5 / FR-001~008 / NFR-001~004 / SC-001~005 / 决策 D1~D4）
- [x] **T002** 立项 [ADR-0066](../../adr/0027-schema-normalization-and-item-granular-fulfillment.md)（列序规范化 + 按 order_item 粒度履约 + order_item_no 引入）+ `docs/adr/README.md` 注册

## 批次 B — DDL 规范化（依赖：无）

- [x] **T003** 全表列序审查结论入档：[plan.md §3 附录](plan.md#3-附录22-张表列序审查结论t003-落物)（22 张逐表目标列序；违规 5 张 + order_items 连带）
- [x] **T004** order_item_no 落地：`BusinessNoType` 加 `ORDER_ITEM("OI")`；order_items 加列（第 2 位，`uk_order_items_order_item_no`）；`fulfillments.order_item_id` / `refund_items.order_item_id` 语义升级为 OI 单号（列名不改）
- [x] **T005** 迁移脚本 `deployment/schema/018-schema-normalization.sql`（幂等 information_schema + PREPARE；禁用 MariaDB 方言）：
  - order 库：order_items 加列 → 回填 `CONCAT('OI', id)` → NOT NULL + 唯一键 → 列序链
  - payment 库：payment_attempts 加 amount_minor/currency_code → JOIN payments 回填 → NOT NULL → 列序（requested_at/responded_at/version 最后三列）；payments / refunds 列序调整
  - fulfillment 库：存量 NULL 回填 `LEGACY-{id}` → order_item_id NOT NULL → source_payment_no 提第 2 位 → 一条 ALTER 换 uk 为 `(source_payment_no, order_item_id)`
  - entitlement / settlement 库列序调整
- [x] **T006** 基线 CREATE TABLE 同步：`01-order-schema.sql` / `03-payment-schema.sql` / `04-fulfillment-schema.sql` / `05-entitlement-schema.sql` / `08-settlement-schema.sql`
- [x] **T007** H2 测试 schema.sql 同步（order / payment / fulfillment / entitlement / settlement 5 处）

## 批次 C — 按 order_item 粒度履约（依赖：批次 B）

- [x] **T008** common-dto：`PaymentSucceededRequest` 加 `List<ItemLine> items`（orderItemNo/skuCode/name/quantity/priceMinor/currencyCode）；RpcContractTest 同步
- [x] **T009** order-service：`OrderItem` 加 orderItemNo（下单时生成）；`onPaymentSucceeded` 通知携带明细；`OrderItemEntity` / 仓储映射同步
- [x] **T010** fulfillment-service：`acceptPaymentSucceeded` 循环逐明细建单；幂等查询改 `findBySourcePaymentNoAndOrderItemId`；`newFulfillment(orderNo, orderItemId, sourcePaymentNo)` 消除 null 硬编码；`findByOrderNo` 改返回 List，`onRefund` 遍历取消全部 PENDING；每条履约各自通知 entitlement（授予链零改动）
- [x] **T011** payment-service：`PaymentAttempt` / Entity / 仓储加 amountMinor + currencyCode；两个创建点（`PaymentPersistence`、`PaymentRefundService.recordRefundChannelAttempt`）写入；测试断言补充

## 批次 D — demo 中文注释 + 门户主界面（依赖：无，可与批次 C 并行）

- [x] **T012** 全链路 DB 数据中文注释：`DemoDbTraceController` 三个封装加 label 参数（约 15 个调用点，标签清单见 [plan.md §2.4](plan.md#24-demo-中文注释实现方式问题-f)）；`demo.html` 标题行 1 行适配
- [x] **T013** 门户主界面：新建 `static/portal.html`（卡片式：演示控制台 / 对账控制台 / Grafana 3000 / Prometheus 9090 / 压测入口含 `deployment/performance/run-stress.sh` 与 `deployment/demo-monitor-stress.sh` 用法）；`PageController` 加 `/` 欢迎页路由
- [x] **T014** demo live 冒烟：下单 2 item → 2 条 fulfillments（OI 单号）→ payment_attempts 带金额 → portal 各入口可达

## 批次 E — 收尾

- [x] **T015** 全量回归：各模块单测 → `mvn -o clean verify -fae`
- [x] **T016** 文档收口：spec/ADR 状态 Draft→（实施后）已实施；tasks 勾结；`016-refund-channel-attempt.sql` MariaDB 方言问题记入待办清单（不在本 spec 范围）

## 已知边界（不在本 spec 处理）

- 同一订单同 SKU 多行时 stock confirm 幂等键 `order:{orderNo}:sku:{skuId}` 撞键（既有行为，记录备查）。
- 退款链路重设计 → [spec 019](../019-order-driven-refund/spec.md)。
