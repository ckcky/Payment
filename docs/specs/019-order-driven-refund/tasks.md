# Tasks: 019-order-driven-refund

> 承载目标：order 驱动退款 + 两层退款单（TXRF/PMRF 互记）+ 渠道退款异步回调闭环。
> **当前状态：T101~T114 全部完成（2026-09-07），spec 019 闭环**。全量回归 BUILD SUCCESS；live 冒烟通过。
> 每个任务完成后跑对应模块测试门禁，最后统一 `mvn -o clean verify -fae`。

## 批次 A — 文档与决策（已完成）

- [x] **T101** 编写 spec 019 四件套：spec.md（业内对比 / 目标链路 / US1~US5 / FR-001~010 / NFR / SC / 决策 D1~D8）
- [x] **T102** 立项 [ADR-0067](../../adr/0028-order-driven-refund-two-layer-refund-order.md)（order 驱动两层退款单 + 异步回调闭环）+ `docs/adr/README.md` 注册

## 批次 B — order 侧（依赖：spec 018 批次 B/C）

- [x] **T103** DDL：order 库新增 `transaction_refunds`（列序守 018 规范，DDL 见 [plan.md §2.1](plan.md#21-order-库transaction_refunds新增)）；`transactions` 加 `payment_no`（生效支付单）+ `refunded_minor`；payment 库 `refunds` 加 `transaction_refund_no`；迁移脚本 `019-order-driven-refund.sql`（幂等）+ H2 schema 同步
- [x] **T104** 发起链：`TransactionApplicationService.createRefund()` 生成 TXRF → 落退款单（幂等键=TXRF，可重入）→ `PaymentGateway.refund(transactionRefundNo, paymentNo, ...)` → 响应 PMRF 回填 `payment_refund_no`；`surplusRefund()` 改走此路径（废弃 `autorefund:` 字符串幂等键）；新增 `POST /internal/orders/refund`（校验 PAID + `Order.recordRefund()`/`getRefundableMinor()` 激活）；Feign 契约双号互传
- [x] **T105** 回调收口：新增 `POST /internal/orders/on-refund-result`（TXRF+PMRF 双号）→ transaction 层终态 + `transactions.refunded_minor` 累加 → order 层 `recordRefund()` + `OrderStatus` 补 `PARTIALLY_REFUNDED`/`REFUNDED` → 秒杀商品 catalog 回补（幂等键 `refund:{TXRF}:sku:{skuId}`，普通商品不回补）→ fulfillment 终止（按 item 撤 PENDING）→ entitlement 撤销沿既定链；每步幂等可重入
- [x] **T106** order 侧测试：创建/重试可重入/状态推进；超退二次校验；重复回调幂等；秒杀回补触发条件（普通商品不触发）

## 批次 C — payment 侧（依赖：批次 B 契约定稿）

- [x] **T107** 双层单号落地：`BusinessNoType` 加 `PAYMENT_REFUND("PMRF")`；refunds.refund_no 改自生成 PMRF+雪花（存量 RF 保留）；refunds 加 `transaction_refund_no`（普通索引）；`CreateRefundCommand` 必填 transactionRefundNo，幂等键 = transaction_refund_no（同 TXRF 重试回放同一执行单）；响应携带 PMRF
- [x] **T108** 退款异步回调闭环：`MockChannelAdapter.refund()` 改受理+异步模式（同步模式保留可配）；新增渠道退款回调端点（验参防重放）；`RefundResultProcessor` 统一后处理（attempts/refunds/payments 状态 + ledger 冲正 → 通知 order 带双号）；**同步/异步/resolve 三路收敛到同一编排**；payments 退款口径（refunded_minor 或状态补退款态）与编排归属定稿并回写 [plan.md §3.3](plan.md#33-编排归属t108-实施定稿)
- [x] **T109** 下线与修复：删除 `POST /internal/refunds` 创建入口（resolve 保留）；演示脚本 `scenario-refund.sh` 改调 order 入口；修记账幂等键双重前缀（统一 `REFUND:{PMRF}`）；`PaymentResultProcessor` 不再静默吞异常（WARN + 指标）
- [x] **T110** payment 侧测试：PMRF 生成/幂等键切换/同 TXRF 回放；三路收敛一致性；回调重放幂等；RefundFactsService 对账口径（PMRF + channel_reference）

## 批次 D — mock 渠道与演示（依赖：批次 B/C）

- [x] **T111** mock-channel-web：退款异步回调推送支持（受理 → 延迟 → 回调）；demo 页面退款链路可观测
- [x] **T112** 演示冒烟：手工退款 + surplus 自动退款 → ④ 区 transaction_refunds 与 refunds 状态一致（TXRF↔PMRF 互记）、冲正分录、履约终止、权益撤销、秒杀回补可见；存量 RF 单号数据展示核对

## 批次 E — 收尾

- [x] **T113** 全量回归：各模块单测 → `mvn -o clean verify -fae`
- [x] **T114** 文档收口：spec/ADR 状态推进；ADR-0064 supersedes 关系梳理（自动退款决策演进链）；CHANGELOG

## 明确不做（负责人 2026-09-07 拍板）

- 退款 UNKNOWN 自动收敛器（对标支付侧 ChannelQueryService）——回调丢失靠 resolve 兜底。
- resolve 端点 Admin Token 鉴权。
- 部分退款次数上限（微信式 50 次）——记 NFR 备注。
