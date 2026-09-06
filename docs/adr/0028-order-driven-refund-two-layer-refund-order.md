<a id="adr-0067"></a>

# ADR-0067: order 驱动的两层退款单模型与退款异步回调闭环（spec 019 立项）

- 状态：✅ **Accepted**（2026-09-07 负责人拍板 D1~D8；**代码未实施**，任务见 [spec 019 tasks](../specs/019-order-driven-refund/tasks.md)）
- 关联：ADR-0054（支付编排职责归位，本 ADR 为其在退款方向的落地）、ADR-0062（业务单号体系，扩展 TXRF/PMRF 前缀）、ADR-0063（跨服务按业务单号关联）、ADR-0064（一交易多支付单 / 退款域并入 payment）、spec 018（前置：order_item_no / 列序规范 / 按 item 履约）、spec 019（[spec](../specs/019-order-driven-refund/spec.md) / [plan](../specs/019-order-driven-refund/plan.md)）
- 需求源头：负责人 2026-09-07 退款链路评审——提出「order → transaction → payment → attempts → 渠道」发起链与「payment（attempts/payment 状态+账务）→ order（transaction 状态/订单状态/秒杀回补/履约终止）」回调链；追问「transaction 不记录 paymentNo？」「多次退款总金额校验在哪」「退款单号怎么记录，对比业内」；最终拍板单号前缀 TXRF/PMRF。

## 背景

现状核实（G1~G7，证据见 [spec 019 §当前代码现实](../specs/019-order-driven-refund/spec.md)）：order 是退款链路孤儿（`Order.recordRefund()` 死代码、OrderStatus 无退款态、退款成功不通知 order）；transaction 层零持久化退款记录（仅字符串幂等键 `autorefund:{txnNo}:{payNo}`）；渠道退款无异步回调；同步成功与人工 resolve 收敛行为不一致；记账幂等键双重前缀；surplus 退款失败被静默吞掉。

**业内调研（2026-09-07 核实官方文档）**：支付宝 `out_request_no`（同交易多次退款唯一、同号重试只退一次）、微信 `out_refund_no`（最多 50 次部分退、重试用原单号、结果靠回调/查单）、Stripe Refund 独立对象（自有状态机 + balance_transaction）、国内支付中台两层单据模式（交易系统退款单 + 支付系统执行单互相关联、原单累加已退防超退、幂等可重入）。**共识：退款不生成新交易号，但每笔退款一张独立退款单一个新单号；交易层必须持久化退款单并累加已退金额。**

## 决策（负责人 2026-09-07 逐条拍板）

1. **链路方向**：order 发起 → transaction 层 → payment → payment_attempts 落尝试 → 渠道；渠道异步回调 → payment（attempts/payment 状态 + 账务冲正）→ order（transaction 状态 / 订单状态 / 秒杀库存回补 / 履约终止）。对齐 ADR-0054。

2. **退款单号：双层单据模式**（业内中台模式，负责人最终拍板；此前一度倾向微信式单层）：
   - transaction 层生成 **TXRF+雪花**（交易层退款单号，`BusinessNoType.TRANSACTION_REFUND("TXRF")`），持 `transaction_refunds` 表（1 transaction : N 退款单，幂等键=TXRF，重试同号可重入）；
   - payment 层生成 **PMRF+雪花**（退款执行单号，`BusinessNoType.PAYMENT_REFUND("PMRF")`；存量 RF 前缀保留不改写）；
   - **两层互记对方单号**：`transaction_refunds.payment_refund_no` ↔ `refunds.transaction_refund_no`，RPC 请求带 TXRF、响应回 PMRF；
   - 对账口径以 PMRF + 渠道 channel_reference 为准（渠道事实在支付层）。

3. **transactions 表补齐资金口径**：加 `payment_no`（**生效支付单**——首张成功支付，与 orders.payment_no 同步；transaction:payment=1:N，surplus 被退单不覆盖、记在 transaction_refunds 行级）+ `refunded_minor`（累加已退）。

4. **多次退款总金额校验两层**：payment 受理层权威（`RefundPolicy` 累计已退+本次≤实付，`refund_intake_locks` 悲观锁串行化，在途保守占用——业内「原单累加已退校验不可超」）；order/transaction 层第二道（`transactions.refunded_minor` + `Order.recordRefund()` 激活）。多次退款 = 多个 TXRF + 多个 PMRF 一一对应。

5. **渠道退款异步回调闭环（新增）**：`MockChannelAdapter` 改受理+异步模式；新增退款回调端点（验参防重放）；`RefundResultProcessor` 统一后处理——**同步受理成功 / 异步回调 / resolve 人工收敛三路收敛到同一编排**（顺带消除「UNKNOWN 收敛后处理断链」，不设专项任务）。

6. **order 侧逆向联动**：订单状态补 `PARTIALLY_REFUNDED`/`REFUNDED`；秒杀商品 catalog 库存回补（幂等键 `refund:{TXRF}:sku:{skuId}`，普通商品不回补）；fulfillment 终止（spec 018 后按 item 撤 PENDING）；entitlement 撤销沿 fulfillment→entitlement 既定链（order 不直调）。

7. **收敛发起路径**：下线 payment `POST /internal/refunds` 创建入口（resolve 保留），统一走 order；演示脚本改造。顺手修复：记账幂等键双重前缀（统一 `REFUND:{PMRF}`）、`PaymentResultProcessor` 静默吞 surplus 退款失败异常（WARN+指标）。

8. **明确不做**（负责人拍板）：退款 UNKNOWN 自动收敛器；resolve 端点 Admin Token 鉴权；部分退款次数上限（记 NFR 备注）。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| 微信式单层单号（transaction 生成 RF，payment 复用） | 负责人最终选择双层：两层各自持单、互记对方，语义更完备（此前一度拍板单层后修正） |
| Stripe 式（payment 生成、transaction 仅记录） | transaction 层重试需先查后发，幂等控制权旁落 |
| 复用 RF 前缀给两层 | 两层单号无法从字面区分归属 |
| 保持 payment 扇出编排现状 | 与 ADR-0054「order 编排中心」冲突；order 侧无退款单载体 |
| 继续只做同步三态退款 | 长时退款场景结果无收敛通道；与支付侧能力不对称 |

## 影响

- **正影响**：order 成为完整业务编排者（正向+逆向闭环）；交易层资金口径自洽（payment_no/refunded_minor）；多次退款可追溯可重入；退款结果收敛通道与支付侧对称；已知断链（G4/G5/G6）一并消除。
- **代价**：order 库新增一表两列、payment 库一列一前缀迁移；mock 渠道异步化改造；演示脚本与对账口径随动；三路收敛重构 RefundPostProcessOrchestrator 归属（T108 定稿）。
- **不做**：UNKNOWN 自动收敛器、resolve Admin Token、部分退款次数上限（负责人明确忽略/记备注）。
