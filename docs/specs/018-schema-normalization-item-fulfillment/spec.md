# Feature Specification: 表结构规范化 + payment_attempts 金额留痕 + 按 order_item 粒度履约 + demo 注释与门户主界面

**Feature Branch**: `018-schema-normalization-item-fulfillment`

**Created**: 2026-09-07

**Status**: ✅ Accepted（2026-09-07 负责人逐项拍板 4 项决策，见 [ADR-0066](../../adr/0027-schema-normalization-and-item-granular-fulfillment.md)；**代码未实施**，任务见 [tasks.md](tasks.md)）

**Input**: 负责人需求原文：

> 「payment attempts 里要记录上金额信息，还有调整下 attempts 的表结构，把 request_at, response_at, version 放到最后三列，attempt_type 放到第四列，其他的相对顺序保持不变。」
> 「审查每个表的结构，第一列是 mysql 的自增 id，第二列必须是这个表的主键，然后是唯一索引（如果有的话）。」
> 「fulfillment-service · fulfillments 这个表为什么没有 order_item_id，踏马的要有才行啊。」
> 「在 demo 的展示里"全链路 DB 数据"这块标注上注释 如 order-service · orders（订单记录）。」
> 「demo 界面搞个主界面，点击可以跳转 PaymentArch 演示控制台、对账控制台、Grafana 监控、Prometheus、压测入口都放在主界面里。」

> 本 Feature 是**规范化型 + 补洞型** Spec：① 确立全项目表结构列序规范并修复违规表；② payment_attempts 补金额留痕；③ 修复 fulfillments.order_item_id 恒 NULL 并把履约升级为按订单明细粒度；④ demo 全链路 DB 数据加中文注释 + 新增门户主界面。退款链路重设计**不在本 Feature**，单独立项为 spec 019（见 §范围外）。

## 当前代码现实（已核实，禁止按绿地项目理解）

| # | 缺口 | 代码 / 文档证据 | 影响 |
|---|---|---|---|
| **G1** | payment_attempts 无金额字段 | `deployment/schema/03-payment-schema.sql:34-56` 只有 payment_no/channel_code/attempt_type/时间/渠道引用/状态列，金额只在其父表 payments | 渠道交互流水无金额证据，排查与对账金额比对缺依据；退款尝试也看不出退了多少 |
| **G2** | 5 张表列序违反新规范 | 规范 = 第 1 列自增 id、第 2 列业务主键、然后是唯一索引列。违规：`payments`（idempotency_key 第 7 列）、`refunds`（第 9 列）、`fulfillments`（uk 列 source_payment_no 第 4 列）、`entitlements`（第 4 列）、`settlement_batches`（第 10 列） | 表结构不可预测，新人读表 / DBA 排查成本高；与 ADR-0062 单号体系倡导的「单号即身份」不一致 |
| **G3** | fulfillments.order_item_id 列存在但恒 NULL | 列在 `04-fulfillment-schema.sql:10`（可空）；但创建履约硬编码传 null：`FulfillmentApplicationService.java:72` `new Fulfillment(orderNo, null, "mock delivery", sourcePaymentNo)` | 履约与订单明细零关联；demo 里该列全是 NULL；按明细退款/撤销无落点 |
| **G4** | order_items 无业务单号 | `OrderItem` 领域对象只有自增 id（`01-order-schema.sql` order_items 表）；`PaymentSucceededRequest` 不携带明细 | 明细无法跨服务引用（违反 ADR-0063 精神：数值主键不出服务边界）；refund_items.order_item_id 现网几乎空转（surplus 路径传空列表、demo 不传 items） |
| **G5** | demo 无中文注释、无门户主界面 | `DemoDbTraceController` 约 15 个 section 只有 `system · table` 英文标签；mock-channel-web:8091 有 demo/cashier/audit 三页但无聚合入口 | 演示时观众需自行猜每块数据是什么；入口分散（Grafana 3000 / Prometheus 9090 / 压测脚本） |

### 兼容性事实（决定方案边界）

- 全项目共 **25 张表**（order 3 + catalog 4 + payment 6 + fulfillment 1 + entitlement 1 + reconciliation 1 + settlement 3 + ledger 3 + audit 3）；3 张特例表**豁免**列序规范（负责人拍板）：`refund_intake_locks`（纯锁表，主键 payment_no 无自增 id）、`accounts`（ledger 预置科目，id 固定 1-5 非自增）、`stock_reservation`（主键 reservation_id 非自增）。
- entitlement 授予幂等键 = fulfillment 行主键、撤销按 orderNo 全量撤 → **天然兼容一单多条履约，授予/撤销链零改动**；stock confirm 幂等键 `order:{orderNo}:sku:{skuId}` 与履约无关 → **零改动**。
- 生产 DDL 无 Flyway，迁移靠幂等脚本（`deployment/schema/015-*.sql` 的 information_schema + PREPARE 模式）；**016 脚本用了 `ADD COLUMN IF NOT EXISTS`（MariaDB 语法，MySQL 8 报错）——本次不沿用**，修复 016 记为待办不在本 Feature。
- `run-stress.sh` 已随 v1.0.0 整理迁至 `deployment/performance/run-stress.sh`（2026-09-07 git pull 确认）。

## 决策记录（负责人 2026-09-07 逐项拍板）

| # | 决策点 | 拍板结果 |
|---|---|---|
| D1 | fulfillments.order_item_id 怎么修 | **按 order_item 粒度建履约**：每个订单明细一条履约，order_item_id NOT NULL，唯一键改 `(source_payment_no, order_item_id)` |
| D2 | payment_attempts 加哪些金额列 | **amount_minor BIGINT NOT NULL + currency_code VARCHAR(8) NOT NULL**，插在 attempt_type 之后（第 5、6 列）；PAYMENT 尝试记支付金额、REFUND 尝试记所属支付单金额 |
| D3 | 列序规范遇特例表 | **特例保留现状**（refund_intake_locks / accounts / stock_reservation），审查报告单独标注豁免 |
| D4 | 退款链路断链 | **本次 spec 外，单独立项**（→ spec 019） |

## 用户故事与验收标准

### US1：payment_attempts 金额留痕
**As** 支付平台开发者/排查者，**I want** 每条渠道交互尝试（支付/退款）自带金额与币种，**so that** 不用回查父表就能确认该次渠道交互的资金口径。
- **AC1.1** payment_attempts 新增 `amount_minor` / `currency_code` 列，存量数据按所属支付单回填。
- **AC1.2** 新建支付尝试（PaymentPersistence）与退款尝试（recordRefundChannelAttempt）均写入金额列。

### US2：全表列序规范化
**As** 开发者/DBA，**I want** 每张表按「自增 id → 业务主键 → 唯一索引列 → 其余业务列 → 审计列」排序，**so that** 表结构可预测、单号列一眼可见。
- **AC2.1** 5 张违规表（payments / refunds / fulfillments / entitlements / settlement_batches）列序调整到位。
- **AC2.2** order_items 因新增 order_item_no（第 2 列）同步调整列序。
- **AC2.3** 3 张特例表保留现状并在 plan.md 附录标注豁免。
- **AC2.4** 幂等迁移脚本可重放；基线 CREATE TABLE 与 H2 测试 schema 同步。

### US3：按订单明细粒度履约
**As** 履约服务，**I want** 每个 order_item 一条履约记录且 order_item_id 为真实业务单号，**so that** 履约可按明细追踪、按明细退款/撤销有落点。
- **AC3.1** order_items 新增 `order_item_no`（OI+雪花，第 2 列，唯一键）。
- **AC3.2** 支付成功通知携带明细列表；fulfillment 逐明细建单，order_item_id = OI 单号。
- **AC3.3** 幂等粒度细化到 `(source_payment_no, order_item_id)`；重复通知不产生重复履约。
- **AC3.4** 退款时 `on-refund` 取消该订单全部 PENDING 履约（entitlement 授予/撤销链零改动）。

### US4：demo 全链路 DB 数据中文注释
**As** 演示观众，**I want** ④ 全链路 DB 数据每个区块标题带中文说明，**so that** 一眼看懂数据归属。
- **AC4.1** 约 15 个 section 标题形如 `order-service · orders（订单主表）`。
- **AC4.2** 标签单一事实源在后端，前端仅一行适配 + 兜底。

### US5：门户主界面
**As** 演示者，**I want** 一个主页面聚合所有控制台与工具入口，**so that** 演示不散页。
- **AC5.1** `http://localhost:8091/`（欢迎页）卡片聚合：演示控制台 /demo、对账控制台 /audit.html、Grafana http://localhost:3000、Prometheus http://localhost:9090、压测入口（`deployment/performance/run-stress.sh` / `deployment/demo-monitor-stress.sh` 用法 + 一键复制）。
- **AC5.2** 风格与 demo.html / audit.html 一致；入口附用途说明。

## 功能需求（FR）

- **FR-001** payment_attempts 列目标顺序：`id, payment_no, channel_code, attempt_type, amount_minor, currency_code, channel_reference, status, failure_reason, retry_count, error_type, created_at, updated_at, created_by, updated_by, requested_at, responded_at, version`（requested_at/responded_at/version 最后三列）。
- **FR-002** 列序规范（新增长效约束，入 ADR-0066）：第 1 列自增 id；第 2 列业务主键；随后是唯一索引列（如有）；其余列相对顺序保持稳定；审计列（created_at/updated_at/created_by/updated_by/version）殿后。特例表豁免清单见 §决策 D3。
- **FR-003** order_items 新增 `order_item_no VARCHAR(32) NOT NULL`（OI+雪花），第 2 列，`uk_order_items_order_item_no`；`fulfillments.order_item_id`、`refund_items.order_item_id` 语义升级为存 OI 单号（**列名不改**）。
- **FR-004** fulfillments：`order_item_id VARCHAR(64) NOT NULL`（第 3 位），`source_payment_no` 提至第 2 位，唯一键改 `uk_fulfillments_source_payment_item (source_payment_no, order_item_id)`。
- **FR-005** `PaymentSucceededRequest` 增加 `items` 明细列表（orderItemNo/skuCode/name/quantity/priceMinor/currencyCode）；调用点一次性改齐（同仓同版本，无滚动兼容问题）。
- **FR-006** 迁移脚本 `deployment/schema/018-schema-normalization.sql`，幂等可重放（information_schema 守卫 + PREPARE，禁用 MariaDB 方言）。
- **FR-007** demo section 标签：后端 `DemoDbTraceController` 三个封装方法加 label 参数；前端 `demo.html` 一行适配。
- **FR-008** portal.html 门户主界面 + PageController `/` 路由（欢迎页）。

## 非功能需求（NFR）

- **NFR-001** `MODIFY COLUMN ... AFTER` 为 COPY 重建，demo/开发数据量级可接受；脚本注释注明生产执行放低峰。
- **NFR-002** 迁移脚本幂等：重复执行不报错、不产生重复列/索引。
- **NFR-003** MyBatis-Plus 按列名映射，列序调整不要求实体字段顺序变更（仅新增字段）。
- **NFR-004** 微信式「单笔订单部分退款次数上限」不在本 Feature（退款语义见 spec 019）。

## 成功标准（SC）

- **SC-001** `mvn -o clean verify -fae` 全绿。
- **SC-002** demo live 冒烟：下单 2 个 item → ④ 区可见 2 条 fulfillments 且 order_item_id 为 OI 单号；payment_attempts 行带金额/币种。
- **SC-003** ④ 区每个 section 标题带中文说明。
- **SC-004** `http://localhost:8091/` 门户页可达，5 类入口全部可跳转。
- **SC-005** 018 迁移脚本在已有存量数据的库上重放成功。

## 依赖与风险

- **依赖**：spec 016（payment_attempts/attempt_type 已存在）；ADR-0062/0063（单号体系）。
- **风险**：Feign 契约变更（PaymentSucceededRequest 加字段）需 common-dto 与全部调用点同版本发布（单体仓库同 commit，无灰度窗口）；fulfillments 存量 NULL 回填 `LEGACY-{id}` 保证新 uk 唯一。
- **范围外**：退款链路重设计（→ spec 019）；016 迁移脚本 MariaDB 方言修复（记待办）；同一订单同 SKU 多行时 stock confirm 幂等键撞键（既有边界，不在本次处理）。
