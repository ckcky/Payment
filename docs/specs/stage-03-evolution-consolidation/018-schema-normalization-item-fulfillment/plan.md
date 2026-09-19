# Plan: 018-schema-normalization-item-fulfillment

> 技术方案版。状态：✅ Accepted（2026-09-07 负责人拍板 D1~D4，见 [ADR-0066](../../adr/0027-schema-normalization-and-item-granular-fulfillment.md)）。代码未实施。

## 1. 方案总览

四个交付面，按依赖排序：**DDL 规范化（含 payment_attempts 金额列）→ 按 order_item 粒度履约 → demo 中文注释 → 门户主界面**。前两者共享一次迁移脚本与基线 DDL/测试 schema 同步；后两者同属 mock-channel-web 模块。

## 2. 关键决策与技术依据

### 2.1 order_item_no 取值方案（问题 A）

**决策：order_items 新增 `order_item_no VARCHAR(32) NOT NULL`，格式 `OI + 雪花`（`BusinessNoType` 加 `ORDER_ITEM("OI")`）。**

- 方案一（自增 id 字符串出边界）：**否决**——违反 ADR-0063「数值主键不出服务边界」；demo reset 重建库后历史关联语义崩坏；"7" 这种值对账排查无意义。
- 方案二（OI+雪花，采纳）：与 order_no/payment_no/refund_no/transaction_no 完全同构（ADR-0062）；`fulfillments.order_item_id VARCHAR(64)`、`refund_items.order_item_id VARCHAR(64)` 直接存 OI 单号，**列名不改、只改语义**，省一次跨 3 服务的列名迁移。
- 影响：entitlement 与对账（按 channel_reference 寻址）**零影响**。

### 2.2 履约粒度与下游连锁（问题 B）

- **entitlement 零改动**：授予幂等键 = fulfillment 行主键（多条履约各自唯一）；撤销按 orderNo 全量撤（`revokeOnRefund` 不感知履约条数）。
- **stock confirm 零改动**：幂等键 `order:{orderNo}:sku:{skuId}` 与履约无关。
- fulfillment-service 改动点：`acceptPaymentSucceeded` 循环 items 逐条建履约；幂等查询 `findBySourcePaymentNoAndOrderItemId`；`newFulfillment(orderNo, orderItemId, sourcePaymentNo)` 消除 `FulfillmentApplicationService.java:72` 的 null 硬编码；`FulfillmentRepository.findByOrderNo` 返回类型 `Optional → List`，`onRefund` 遍历取消全部 PENDING（响应值 CANCELLED/SKIPPED 语义不变，兼容上游）。

### 2.3 迁移脚本（问题 C）

`deployment/schema/018-schema-normalization.sql`，幂等模式**沿用 015 的 information_schema 守卫 + PREPARE 动态 SQL**；**禁用 016 的 `ADD COLUMN IF NOT EXISTS`（MariaDB 方言，MySQL 8 报错）**。修复 016 记为独立待办。

分段清单：

```sql
-- ① USE `order`
-- order_items 加列（先 NULL 便于回填）→ 回填 → 收紧 + 唯一键 + 列序链
ALTER TABLE order_items ADD COLUMN order_item_no VARCHAR(32) NULL;
UPDATE order_items SET order_item_no = CONCAT('OI', id) WHERE order_item_no IS NULL;  -- 'OI'+短数字不与 19 位雪花撞号
ALTER TABLE order_items
  MODIFY COLUMN order_item_no VARCHAR(32) NOT NULL AFTER id,
  ADD UNIQUE KEY uk_order_items_order_item_no (order_item_no);
-- 列序链：order_no 顺延至第 3 位，其余列 AFTER 逐列串起

-- ② USE `payment`
-- payment_attempts：加金额列 → 回填 → 收紧 → 列序
ALTER TABLE payment_attempts
  ADD COLUMN amount_minor BIGINT NULL AFTER attempt_type,
  ADD COLUMN currency_code VARCHAR(8) NULL AFTER amount_minor;
UPDATE payment_attempts a JOIN payments p ON a.payment_no = p.payment_no
  SET a.amount_minor = p.amount_minor, a.currency_code = p.currency_code
  WHERE a.amount_minor IS NULL;   -- 退款尝试记所属支付单金额（口径：渠道交互对应支付单金额）
ALTER TABLE payment_attempts
  MODIFY COLUMN amount_minor BIGINT NOT NULL,
  MODIFY COLUMN currency_code VARCHAR(8) NOT NULL;
-- 列序链：requested_at/responded_at/version 挪最后三列（目标序见 spec FR-001）
-- payments / refunds：纯列序调整（每条 MODIFY 带全 NOT NULL/DEFAULT/COMMENT 定义）

-- ③ USE `fulfillment`
UPDATE fulfillments SET order_item_id = CONCAT('LEGACY-', id) WHERE order_item_id IS NULL;  -- 存量=整单一条履约，保新 uk 唯一
ALTER TABLE fulfillments
  MODIFY COLUMN order_item_id VARCHAR(64) NOT NULL AFTER order_no,
  DROP INDEX uk_fulfillments_source_payment_no,
  ADD UNIQUE KEY uk_fulfillments_source_payment_item (source_payment_no, order_item_id);
-- source_payment_no 提至第 2 位（同一条 ALTER 或紧随其后，防中间态）

-- ④ USE `entitlement` / ⑤ USE `settlement`
-- 纯列序调整（entitlements: source_fulfillment_id 提第 2 位；settlement_batches: idempotency_key 提至 batch_no/merchant_id/period 之后）
```

注意：`MODIFY COLUMN ... AFTER` 在 MySQL 8 是 COPY 重建，demo/开发量级无压力；脚本注释注明生产放低峰。

### 2.4 demo 中文注释实现方式（问题 F）

**后端单一事实源**：`DemoDbTraceController` 的 `query()` / `emptySection()` / `errorSection()` 三个私有封装加 `label` 参数（约 15 个调用点），`section.put("label", label)`；前端 `demo.html` 标题行改 1 行：`esc(s.label || (s.system + ' · ' + s.table))`（保留兜底）。否决前端映射表方案（每次调 SQL/表名要两处同步）。

标签清单（约 15 处）：orders（订单主表）/ order_items（订单明细）/ transactions（交易单）/ payments（支付单）/ payment_attempts（渠道交互尝试记录）/ refunds（退款单）/ fulfillments（履约记录，按订单明细）/ entitlements（权益）/ settlement_items（结算明细）/ settlement_batches（结算批次）/ reconciliation_batches（对账批次）/ postings（记账批次）/ ledger_entries（账本分录）/ 退款尝试卡片同款。

### 2.5 门户主界面（T013，探索结论）

现状：全部 Web 控制台都在 mock-channel-web:8091——demo.html（/demo 演示控制台）、cashier.html（/cashier 收银台）、audit.html（/audit.html 对账控制台，LIVE 模式经同源代理 `/proxy/recon` 调 reconciliation-service:8088）；Grafana localhost:3000（admin/admin，预置看板 "PaymentArch · SRE 黄金指标"）；Prometheus localhost:9090；压测：`deployment/performance/run-stress.sh`（Node 零依赖 loadgen 或 k6，①catalog 缓存读+秒杀 ②全链路 下单→支付→退款，报告落 `deployment/performance/results/`）、`deployment/demo-monitor-stress.sh`（起栈+播种秒杀+持续流量+自动开 Grafana+压测）、秒杀接口 8082 `/internal/stock/seckill/seed|deduct`。

实现（最小改动、与现有模式一致）：

1. 新建 `deployment/mock-channel-web/src/main/resources/static/portal.html`。
2. `PageController.java` 加 `@GetMapping("/")` forward:/portal.html（与 /demo、/cashier 同模式），设为欢迎页。
3. 页面：卡片式入口（演示控制台 / 对账控制台 / Grafana / Prometheus / 压测入口）；每个入口附一句用途说明 + 端口不在线提示；压测卡片展示两条脚本用法命令 + 一键复制；风格复用 demo.html/audit.html 的 CSS 基调。
4. 可选直链：`/proxy/catalog/internal/stock/seckill/seed`（经同源代理）。

## 3. 附录：22 张表列序审查结论（T003 落物）

> 规范：第 1 列自增 id → 第 2 列业务主键 → 唯一索引列（如有）→ 其余业务列（相对顺序不变）→ 审计列殿后。变更部分加粗。

| 表 | 现状结论 | 目标列序 |
|---|---|---|
| orders | ✅ 合规 | — |
| order_items | ⚠️ 新增单号后调整 | id, **order_item_no**, order_no, sku_id, sku_code, name, quantity, price_minor, currency_code, created_at, updated_at, created_by, updated_by, version |
| transactions | ✅ 合规（transaction_no 第 2 列，order_no 第 3 列同为 uk 列） | — |
| products | ✅ 合规 | — |
| skus | ✅ 合规 | — |
| stock | ✅ 合规 | — |
| stock_reservation | 豁免（主键 reservation_id 非自增） | — |
| payments | ❌ idempotency_key 第 7 列 | id, payment_no, **idempotency_key**, transaction_id, order_no, user_id, amount_minor, currency_code, attempt_seq, status, current_attempt_id, failure_reason, query_attempts, entered_unknown_at, created_at, updated_at, created_by, updated_by, version |
| payment_attempts | ❌ 需求 1（加金额列 + 列序） | id, payment_no, channel_code, attempt_type, **amount_minor, currency_code**, channel_reference, status, failure_reason, retry_count, error_type, created_at, updated_at, created_by, updated_by, **requested_at, responded_at, version（最后三列）** |
| refunds | ❌ idempotency_key 第 9 列 | id, refund_no, **idempotency_key**, order_no, payment_no, user_id, amount_minor, currency_code, reason, status, failure_reason, created_at, updated_at, created_by, updated_by, version |
| refund_items | ✅ 合规（order_item_id 语义升级为 OI 单号，值变化见 §2.1） | — |
| refund_intake_locks | 豁免（纯锁表） | — |
| refund_post_process_attempts | ✅ 合规 | — |
| fulfillments | ❌ uk 列第 4 位 + order_item_id 可空 | id, **source_payment_no**, order_no, **order_item_id(NOT NULL)**, delivery_content, status, failure_reason, created_at, updated_at, created_by, updated_by, version |
| entitlements | ❌ uk 列第 4 位 | id, **source_fulfillment_id**, user_id, order_no, grant_ref, available_quantity, scope, expiry_at, status, created_at, updated_at, created_by, updated_by, version |
| reconciliation_batches | ✅ 合规 | — |
| settlement_batches | ❌ idempotency_key 第 10 列 | id, batch_no, merchant_id, period, **idempotency_key**, currency_code, income_minor, refund_minor, adjustment_minor, net_minor, status, fact_count, source_period, created_at, updated_at, created_by, updated_by, version |
| settlement_items | ✅ 合规（无 uk，batch_id 第 2 列） | — |
| settlement_adjustments | ✅ 合规（idempotency_key 第 2 列） | — |
| accounts | 豁免（预置科目 id 1-5） | — |
| postings | ✅ 合规 | — |
| ledger_entries | ✅ 合规 | — |
| audit_batches | ✅ 合规 | — |
| audit_differences | ✅ 合规 | — |
| audit_adjustments | ✅ 合规 | — |

**结论：违规 5 张**（payments / refunds / fulfillments / entitlements / settlement_batches）+ payment_attempts（需求 1 自身）+ order_items（新增单号连带）。

## 4. 代码改动清单（问题 D）

| 服务 | 文件 | 改动 |
|---|---|---|
| common | `BusinessNoType` | 加 `ORDER_ITEM("OI")` |
| common-dto | `PaymentSucceededRequest` + RpcContractTest | 加 `List<ItemLine> items` 嵌套 record；调用点一次性改齐 |
| order-service | `OrderItem` / `OrderApplicationService` / `OrderItemEntity` / `MybatisOrderRepository` | 生成 orderItemNo；通知携带明细；实体映射 |
| fulfillment-service | `FulfillmentApplicationService` / `FulfillmentRepository` / Mybatis 仓储 / Mapper | 循环建单、幂等键细化、findByOrderNo 改 List、onRefund 遍历取消 |
| payment-service | `PaymentAttempt` / `PaymentAttemptEntity` / `MybatisPaymentAttemptRepository` / `PaymentPersistence` / `PaymentRefundService` | 金额列贯穿领域-实体-两个创建点 |
| DDL | `01-order` / `03-payment` / `04-fulfillment` / `05-entitlement` / `08-settlement` 基线 + `018-schema-normalization.sql` | 基线同步新库直出目标结构；迁移脚本管存量 |
| H2 | order/payment/fulfillment/entitlement/settlement `src/test/resources/schema.sql` | CREATE TABLE 直改 |
| mock-channel-web | `DemoDbTraceController` / `demo.html` / `portal.html`（新）/ `PageController` | label 参数 + 1 行适配 + 门户页 + 欢迎页路由 |

## 5. 测试策略

- 每任务跑对应模块测试；收尾统一 `mvn -o clean verify -fae`。
- 重点单测：fulfillment 多明细建单/重复通知幂等/部分明细重复；payment 尝试金额断言（PaymentPersistenceTest / PaymentStateMachineTest / RefundTestStack）；RpcContractTest。
- 迁移脚本：对有存量数据的真实 MySQL 库重放两遍验证幂等（FR-006 / SC-005）。
- demo 冒烟：见 acceptance.md。
