# Feature Specification: order 驱动的退款链路重设计——两层退款单 + 渠道退款异步回调闭环

**Feature Branch**: `019-order-driven-refund`

**Created**: 2026-09-07

**Status**: ✅ Accepted（2026-09-07 负责人逐项拍板 8 项决策，见 [ADR-0067](../../adr/0028-order-driven-refund-two-layer-refund-order.md)；**代码未实施**，任务见 [tasks.md](tasks.md)）

**Input**: 负责人 2026-09-07 退款链路讨论（原文归纳）：

> 「我认为应该是调用 order-service 发起退款，order-service 调用 transaction 执行退款，transaction 调用 payment，payment 调用 payment_attempts 然后再调用渠道发起退款。回调通知上来的时候也是按顺序回调 payment（修改 attempts 层状态、payment 层状态，通知账务记账），order（修改 transaction 层状态，order 层订单状态，如果是秒杀商品的话调用 catalog 发起库存回补。调用履约进行终止）。」
> 「transaction 表你不记录 paymentNo 吗……同步调用 payment 的时候会给你返回 paymentNo 支付层单号的啊，这个单号要加上。」
> 「如果出现多次退款的情况是在哪里做总金额校验的，多次退款的情况这个 RF transaction 单号怎么记录、会生成多个单号吗，你多次调用 payment 的 refund，payment 肯定会生成多个 refundNo 的，我希望你在 transaction 层记录下来。对比下业内是怎么做的。」
> 「payment 层生成的单号改成 PMRF+雪花，交易层 transaction 生成 TXRF+雪花 退款单号。」

> 本 Feature 是**重设计型** Spec：把退款编排中心归位到 order（对齐 ADR-0054），建立交易层/支付层**两层退款单**（TXRF/PMRF 双单号互记），并补上缺失的**渠道退款异步回调**闭环。前置依赖：spec 018（order_item_no / 列序规范先行落地）。

## 当前代码现实（已核实，禁止按绿地项目理解）

| # | 缺口 | 代码证据 | 影响 |
|---|---|---|---|
| **G1** | order 是退款链路孤儿 | 退款成功后 payment 不通知 order；`OrderStatus` 无退款态（`order-service/.../domain/OrderStatus.java`）；`Order.recordRefund()`/`getRefundableMinor()`（含超退校验、`orders.refunded_minor` 列）**全仓零调用方**；surplus 退款成功后订单侧零感知，`refunded_minor` 恒 0 | 订单状态与资金事实脱节；退了款订单还停在 PAID |
| **G2** | transaction 层不记录退款 | `TransactionApplicationService.surplusRefund()` 只透传调 payment，幂等仅靠字符串键 `autorefund:{txnNo}:{payNo}`；`transactions` 表无 payment_no、无 refunded_minor | 交易层对「退过什么、退了多少」零持久化，重试/对账/审计无载体 |
| **G3** | 渠道退款无异步回调 | `MockChannelAdapter.refund()` 同步三态；渠道回调端点只有支付（`ChannelCallbackController`） | 退款结果只能靠受理时同步返回或人工 resolve，长时退款场景断链 |
| **G4** | 同步成功与人工收敛行为不一致 | `RefundRpcCallbackService.resolveRefund()` 只改状态，不走 `RefundPostProcessOrchestrator` | （负责人拍板：本 Feature 通过「三路收敛到同一后处理」顺带消除，不设专项任务） |
| **G5** | 记账幂等键双重前缀 | 编排器传 `"REFUND:" + idempotencyKey`，网关再加前缀 → 实际落库 `REFUND:REFUND:xxx` | 功能无损但不一致，顺手修复 |
| **G6** | surplus 退款失败被静默吞掉 | `PaymentResultProcessor.applyAndNotify` 的 `catch (RuntimeException ignored)` | 退款受理失败无任何告警痕迹 |
| **G7** | payment 手工直调退款入口绕过 order | `RefundController` `POST /internal/refunds`（演示脚本在用） | 两套发起路径，order 侧退款单缺失 |

### 业内调研结论（2026-09-07 核实官方文档）

| 业内方 | 做法 | 对本设计的启示 |
|---|---|---|
| 支付宝 `alipay.trade.refund` | 退款请求号 `out_request_no`：同一交易多次退款必须唯一；部分退款必传；**同号重试只退一次**（幂等载体） | 退款必须有独立单号 + 幂等键 |
| 微信支付 申请退款 | 商户退款单号 `out_refund_no` 必填；单笔交易最多 50 次部分退款每次不同号；**失败重试用原单号**；接口返回仅代表受理，结果靠退款回调/查单；总额不超订单金额 | 上层生成单号可重入 + 异步回调 + 累计校验 |
| Stripe Refund 对象 | Refund 是独立资源 `re_xxx` 挂 payment_intent；多次部分退到退满；自有状态机 pending/succeeded/failed/canceled；关联 balance_transaction | 退款单独立聚合 + 状态机 + 资金流水关联 |
| 国内支付中台实践 | 交易系统生成**逆向退款单**、支付系统生成退款执行单，**两层单据互相关联**；原单累加已退金额防超退；幂等可重入（重试返回成功而非报错）；最大努力通知 | **双层单据模式**（负责人最终采纳）：两层各生成各的单号并互记对方 |

**结论**：退款**不生成新的交易号**（一交易一号），但**每笔退款是一张独立退款单、一个新单号**；交易层必须持久化退款单并累加已退金额（负责人原话「自己不存点什么好像也不太对」成立）。

## 决策记录（负责人 2026-09-07 逐项拍板）

| # | 决策点 | 拍板结果 |
|---|---|---|
| D1 | 链路方向 | order 发起 → transaction 层 → payment → payment_attempts 落尝试 → 渠道；回调 payment（attempts/payment 状态+账务）→ order（transaction 状态/订单状态/秒杀回补/履约终止） |
| D2 | 退款单号归属 | **双层单据模式**：transaction 层生成 **TXRF+雪花**、payment 层生成 **PMRF+雪花**，**两层互记对方单号**（RPC 请求/响应互传） |
| D3 | transactions 表加 payment_no | 加；语义=**生效支付单**（首张成功支付，与 orders.payment_no 同步；transaction:payment=1:N，surplus 被退单记在 transaction_refunds 行级） |
| D4 | 多次退款总金额校验 | 两层：payment 受理层权威（累计已退+本次≤实付，intake 悲观锁，在途保守占用）；order/transaction 层第二道（transactions.refunded_minor 累加 + recordRefund 校验） |
| D5 | 库存回补范围 | **仅秒杀商品**回补秒杀库存，普通商品不动 |
| D6 | payment 直调入口 | **下线** `POST /internal/refunds` 创建入口，统一走 order 发起；演示脚本改造 |
| D7 | 渠道退款异步回调 | **新增**回调端点；mock 渠道改受理+异步模式 |
| D8 | 明确忽略 | UNKNOWN 自动收敛器、resolve 端点 Admin Token——**不做专项任务**（三路收敛统一后 resolve 复用同一编排，G4 断链顺带消除） |

## 目标链路

```
【发起】order API（手工/运维）或 surplus 自动判定（TransactionApplicationService）
  → transaction 层生成 TXRF，落 transaction_refunds（REQUESTED，幂等键=TXRF，可重入）
  → PaymentGateway.refund(transactionRefundNo=TXRF, paymentNo, ...)
      → payment 受理：金额校验（权威层）→ 落 refunds（PMRF+雪花）+ 回记 transaction_refund_no=TXRF
      → payment_attempts 落 REFUND 尝试 → 渠道受理（异步模式返回 PROCESSING）
  ← 响应携带 paymentRefundNo=PMRF → transaction_refunds.payment_refund_no 回填

【回调】渠道退款异步回调 → payment 退款回调端点（验参防重放）
  → RefundResultProcessor 统一后处理：attempts 状态 + refunds 状态 + payments 状态（refunded_minor 累加）
      + ledger 冲正（借 MERCHANT_PAYABLE / 贷 CUSTOMER_CASH，幂等键 REFUND:{PMRF}）
  → 通知 order（带 TXRF+PMRF 双号）
      → transaction 层：退款单终态 + transactions.refunded_minor 累加
      → order 层：Order.recordRefund() + OrderStatus 流转（PARTIALLY_REFUNDED/REFUNDED）
      → 秒杀商品：catalog 库存回补（幂等键 refund:{TXRF}:sku:{skuId}；普通商品不回补）
      → fulfillment 终止（on-refund，spec 018 后按 item 撤 PENDING）
      → entitlement 撤销沿 fulfillment→entitlement 既定链

【收敛】同步受理成功 / 异步回调 / resolve 人工收敛 → 同一 RefundResultProcessor 后处理（三路合一）
```

## 用户故事与验收标准

### US1：交易层持有退款单
**As** order 交易层，**I want** 每笔退款一张持久化退款单（TXRF 单号、金额、状态、幂等键、互记 PMRF），**so that** 多次退款可追溯、重试可重入、对账审计有载体。
- AC1.1 `transaction_refunds` 表 1 transaction : N 退款单；多次退款多个 TXRF 逐行记录。
- AC1.2 发起同步返回后 `payment_refund_no`（PMRF）回填。
- AC1.3 重试同 TXRF 不产生第二张单（可重入回放）。

### US2：两层互记单号
**As** 开发者，**I want** transaction_refunds 记 PMRF、refunds 记 TXRF，**so that** 两层单据可双向关联追溯。
- AC2.1 `transaction_refunds.payment_refund_no` 与 `refunds.transaction_refund_no` 成对出现。
- AC2.2 对账口径以 PMRF + 渠道 channel_reference 为准。

### US3：transactions 记录生效支付单
**As** 开发者，**I want** transactions 表记 payment_no 与 refunded_minor，**so that** 交易层自带资金口径。
- AC3.1 支付成功回调时回填/校验 `transactions.payment_no`（生效支付单，不随 surplus 覆盖）。
- AC3.2 退款终态后 `refunded_minor` 累加正确。

### US4：渠道退款异步回调闭环
**As** 支付平台，**I want** 渠道异步回调驱动退款终态并触发统一后处理，**so that** 同步/异步/人工 resolve 三路行为一致。
- AC4.1 回调更新 attempts → refunds → payments 状态并冲正记账。
- AC4.2 回调通知 order 后订单/交易/履约/权益状态联动正确。
- AC4.3 回调重放幂等（同 PMRF 重复回调吸收）。

### US5：订单与库存联动
**As** order 层，**I want** 退款后订单状态流转 + 秒杀库存回补 + 履约终止，**so that** 业务侧逆向闭环。
- AC5.1 OrderStatus 补 PARTIALLY_REFUNDED/REFUNDED；`Order.recordRefund()` 激活并二次校验超退。
- AC5.2 秒杀商品退款回补秒杀库存；普通商品不回补。
- AC5.3 PENDING 履约全部取消，权益沿既定链撤销。

## 功能需求（FR）

- **FR-001** order 库新增 `transaction_refunds`（列序守 spec 018 规范）：`id, refund_no(TXRF, uk), payment_refund_no(PMRF), transaction_no, order_no, payment_no(被退支付单), user_id, amount_minor, currency_code, status(REQUESTED/PROCESSING/SUCCEEDED/FAILED/REJECTED), reason, idempotency_key, 审计列`。
- **FR-002** `transactions` 表加 `payment_no`（生效支付单）+ `refunded_minor`（累加已退）。
- **FR-003** `BusinessNoType` 加 `TRANSACTION_REFUND("TXRF")` 与 `PAYMENT_REFUND("PMRF")`；`REFUND("RF")` 保留兼容存量；refunds.refund_no 新单走 PMRF+雪花（存量 RF 保留不改写）。
- **FR-004** `refunds` 表加 `transaction_refund_no`（TXRF，普通索引）；`CreateRefundCommand` 必填 transactionRefundNo；payment 幂等键 = transaction_refund_no。
- **FR-005** Feign 契约：退款请求带 transactionRefundNo，响应携带 paymentRefundNo。
- **FR-006** 新增渠道退款回调端点（对标支付回调：验参防重放 + 幂等）；`RefundResultProcessor` 统一后处理（同步/异步/resolve 三路收敛）。
- **FR-007** 新增 `POST /internal/orders/on-refund-result`（带双号）；`POST /internal/orders/refund` 发起入口（校验 PAID + recordRefund 二次校验）。
- **FR-008** 下线 `POST /internal/refunds` 创建入口（resolve 收敛端点保留）；演示脚本 `scenario-refund.sh` 改调 order。
- **FR-009** 修记账幂等键双重前缀（统一 `REFUND:{PMRF}`）；PaymentResultProcessor 不再静默吞 surplus 退款失败异常（WARN + 指标留痕）。
- **FR-010** payments 表退款侧最小改动：加 `refunded_minor` 或状态补退款态（实施时在 plan 定稿，见 tasks T108）。

## 非功能需求（NFR）

- **NFR-001** 回调验参防重放；回调丢失靠 resolve 兜底（本次不建自动收敛器）。
- **NFR-002** 微信式「部分退款次数上限（50 次）」记为备注不实现。
- **NFR-003** 每步下游动作幂等可重入（同 TXRF 重试返回成功而非报错）。
- **NFR-004** 编排归属（payment 扇出保留 vs 下游扇出移交 order，对齐 ADR-0054）实施时定稿并回写 plan.md。

## 成功标准（SC）

- **SC-001** `mvn -o clean verify -fae` 全绿。
- **SC-002** demo 冒烟（手工退款）：④ 区 transaction_refunds（TXRF→PMRF 互记）与 refunds 状态一致、冲正分录落账、履约终止、权益撤销。
- **SC-003** demo 冒烟（surplus 自动退款）：重复支付第二笔成功 → 自动发起退款 → 双层退款单闭环可验证。
- **SC-004** 秒杀商品退款回补秒杀库存；普通商品不回补。
- **SC-005** 演示脚本改走 order 入口后全流程通过。

## 依赖与风险

- **依赖**：spec 018 先行（order_item_no / 列序规范 / 履约按 item 粒度）；ADR-0054（order 编排中心）；ADR-0062/0063（单号体系扩展 TXRF/PMRF）。
- **风险**：回调丢失场景靠 resolve 兜底（无自动收敛器，负责人已知悉）；mock 渠道异步化改造涉及 demo 全链路时序；存量 RF 单号数据与演示脚本前缀引用需随 T107/T112 核对。
- **明确不做**（负责人拍板 D8）：UNKNOWN 自动收敛器；resolve Admin Token 鉴权；部分退款次数上限。
