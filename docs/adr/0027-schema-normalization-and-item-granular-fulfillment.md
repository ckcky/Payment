<a id="adr-0066"></a>

# ADR-0066: 表结构列序规范化与按订单明细粒度履约——order_item_no 业务单号引入（spec 018 立项）

- 状态：✅ **已实施**（2026-09-07 代码落地：批次 B 迁移/基线/H2 同步 + 批次 C 契约与三服务改造 + 批次 D demo 注释与门户；单测全绿，live 冒烟双明细履约/attempts 金额/中文标签通过）
- 关联：ADR-0062（业务单号两字母前缀+雪花）、ADR-0063（跨服务按业务单号关联）、ADR-0064（一交易多支付单）、ADR-0054（支付编排职责归位）、spec 016（payment_attempts/attempt_type 现状）、spec 018（[spec](../specs/018-schema-normalization-item-fulfillment/spec.md) / [plan](../specs/018-schema-normalization-item-fulfillment/plan.md)）
- 需求源头：负责人 2026-09-07 四项指示——「payment attempts 里要记录上金额信息……attempt_type 放到第四列」「审查每个表的结构，第一列是 mysql 的自增 id，第二列必须是这个表的主键，然后是唯一索引」「fulfillments 这个表为什么没有 order_item_id，踏马的要有才行啊」「demo 全链路 DB 数据标注中文注释 + 搞个主界面」。

## 背景

逐项核实发现四类问题：

1. **payment_attempts 无金额列**：渠道交互流水缺资金证据（`03-payment-schema.sql` 金额只在父表 payments）。
2. **列序无规范**：5 张表违反「自增 id → 业务主键 → 唯一索引列」惯例（payments 的 idempotency_key 第 7 列、refunds 第 9 列、fulfillments 的 source_payment_no 第 4 列、entitlements 第 4 列、settlement_batches 第 10 列）。
3. **fulfillments.order_item_id 恒 NULL**：列存在（可空）但创建履约硬编码传 null（`FulfillmentApplicationService.java:72`）——「列在、值没有」，履约与订单明细零关联。
4. **order_items 无业务单号**：明细无法跨服务引用，refund_items.order_item_id 现网几乎空转。

## 决策（负责人 2026-09-07 逐条拍板）

1. **列序规范确立为全项目长效约束**（新表强制，存量表本次修复 5 张）：
   `第 1 列自增 id → 第 2 列业务主键 → 唯一索引列（如有）→ 其余业务列（相对顺序稳定）→ 审计列殿后`。
   **特例豁免**（负责人拍板保留现状）：`refund_intake_locks`（纯锁表）、`accounts`（预置科目 id 1-5）、`stock_reservation`（主键 reservation_id 非自增）。

2. **payment_attempts 加 `amount_minor` + `currency_code`**（第 5、6 列，attempt_type 之后）；PAYMENT 尝试记支付金额，REFUND 尝试记所属支付单金额；存量 JOIN payments 回填；requested_at/responded_at/version 移最后三列。

3. **履约改为按 order_item 粒度**：每个订单明细一条履约；`fulfillments.order_item_id` 改 NOT NULL（值为 OI 单号）；唯一键 `uk_fulfillments_source_payment_no` → `uk_fulfillments_source_payment_item (source_payment_no, order_item_id)`；`PaymentSucceededRequest` 携带明细列表。entitlement 授予/撤销链与 stock confirm **零改动**（幂等键天然兼容，已核实）。

4. **order_items 新增 `order_item_no`（OI+雪花）**：`BusinessNoType` 加 `ORDER_ITEM("OI")`；`fulfillments.order_item_id` / `refund_items.order_item_id` **列名不改、语义升级**为存 OI 单号。自增 id 字符串方案否决（违反 ADR-0063「数值主键不出服务边界」）。

5. **迁移策略**：幂等脚本 `018-schema-normalization.sql`（information_schema + PREPARE，沿用 015 模式）；**禁用 016 脚本的 `ADD COLUMN IF NOT EXISTS`**（MariaDB 方言，MySQL 8 报错——016 修复记独立待办）；fulfillments 存量 NULL 回填 `LEGACY-{id}`。

6. **demo 改造**：全链路 DB 数据 section 标签后端单一事实源（DemoDbTraceController 加 label）；门户主界面 portal.html（聚合演示控制台/对账控制台/Grafana/Prometheus/压测入口，`/` 设为欢迎页）。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| order_item_id 存自增 id 字符串 | 违反 ADR-0063；demo reset 后关联语义崩坏；可读性差 |
| 保持单条履约、order_item_id 填首个/拼接明细 | 列语义含混；按明细退款无落点 |
| 特例表也强制改造 | 锁表/预置科目表强套「自增 id+业务主键」破坏功能语义（负责人裁决豁免） |
| demo 标签做在前端映射表 | 两处维护，SQL/表名调整易漂移 |
| 016 脚本沿用 `ADD COLUMN IF NOT EXISTS` | MariaDB 方言，MySQL 8 直接报错 |

## 影响

- **正影响**：表结构可预测（单号列一眼可见）；渠道流水带金额证据可对账；履约可按明细追踪与撤销；demo 可读性与演示入口体验提升。
- **代价**：一次 COPY 重建式列序迁移（5 库 8 表）；common-dto 契约加字段（单体仓库同版本发布，无灰度窗口）；fulfillment/order/payment 三服务联动改造。
- **不做**：退款链路重设计（→ spec 019 / ADR-0067）；016 脚本方言修复（独立待办）；同订单同 SKU 多行 stock confirm 撞键（既有边界）。
