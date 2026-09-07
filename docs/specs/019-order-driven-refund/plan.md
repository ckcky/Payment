# Plan: 019-order-driven-refund

> 技术方案版。状态：✅ Accepted（2026-09-07 负责人拍板 D1~D8，见 [ADR-0067](../../adr/0028-order-driven-refund-two-layer-refund-order.md)）。代码已实施（批次 A~D，2026-09-07）。§3.3 编排归属定稿见下文。

## 1. 架构总览

```
┌─────────────── order-service ───────────────┐          ┌─────────────── payment-service ─────────────┐
│ order 层（订单状态/库存回补/履约终止）          │          │ 渠道适配层（MockChannelAdapter 异步模式）      │
│ transaction 层（退款单 TXRF / 交易状态）       │ ──Feign── │ 退款域（refunds PMRF / intake 锁 / RefundPolicy）│
│ transaction_refunds 表（1 transaction : N）   │ ←─────── │ payment_attempts（REFUND 尝试）              │
└───────────────┬─────────────────────────────┘  回调/通知  │ RefundResultProcessor（三路收敛统一后处理）    │
                │                                          │ ledger 冲正记账                              │
        catalog（秒杀回补） fulfillment（终止→entitlement 撤销）          └─────────────────────────────────────────────┘
                                              ↑ 渠道异步退款回调
```

## 2. 单据模型

### 2.1 order 库：`transaction_refunds`（新增）

```sql
CREATE TABLE IF NOT EXISTS transaction_refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_no VARCHAR(32) NOT NULL COMMENT '交易层退款单号 TXRF+雪花（ADR-0067）',
    payment_refund_no VARCHAR(32) NULL COMMENT '支付层退款执行单号 PMRF+雪花（响应回填，ADR-0067）',
    transaction_no VARCHAR(32) NOT NULL COMMENT '所属交易（TX+雪花，ADR-0062）',
    order_no VARCHAR(32) NOT NULL COMMENT '所属订单（OR+雪花，ADR-0063）',
    payment_no VARCHAR(32) NOT NULL COMMENT '被退支付单（PM+雪花，ADR-0063）',
    user_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'REQUESTED/PROCESSING/SUCCEEDED/FAILED/REJECTED',
    reason VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
    created_by VARCHAR(64), updated_by VARCHAR(64),
    version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_refunds_refund_no (refund_no),
    UNIQUE KEY uk_transaction_refunds_idempotency_key (idempotency_key),
    KEY idx_transaction_refunds_transaction_no (transaction_no),
    KEY idx_transaction_refunds_payment_refund_no (payment_refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

领域：`RefundOrder` 聚合（状态机 REQUESTED→PROCESSING→{SUCCEEDED|FAILED|REJECTED}，终态吸收 + 乐观锁）；幂等键 = TXRF（同号重试可重入回放）。

### 2.2 `transactions` 表（变更）

加 `payment_no VARCHAR(32) NULL`（**生效支付单**：首张成功支付，与 orders.payment_no 同步；surplus 被退单**不覆盖**此列）、`refunded_minor BIGINT NOT NULL DEFAULT 0`（累加已退）。

### 2.3 payment 库：`refunds` 表（变更）

- `refund_no` 前缀 RF → **PMRF**（`BusinessNoType` 加 `PAYMENT_REFUND("PMRF")`；存量 RF 保留不改写，唯一性无冲突）。
- 加 `transaction_refund_no VARCHAR(32) NULL`（记录上层 TXRF，普通索引 `idx_refunds_transaction_refund_no`）。
- 幂等键改为 **transaction_refund_no**（同 TXRF 重试返回同一执行单，可重入回放——微信模式语义，载体从商户单号变为上层单号）。
- `CreateRefundCommand` 必填 `transactionRefundNo`；`Refund` 聚合自生成单号逻辑改用 PMRF 类型。

### 2.4 单号体系扩展（ADR-0062 登记）

| 前缀 | 归属 | 语义 | 生成方 |
|---|---|---|---|
| TXRF | order transaction 层 | 交易层退款单号 | TransactionApplicationService |
| PMRF | payment-service | 支付层退款执行单号 | RefundApplicationService |
| RF | （保留） | 存量退款执行单号 | 不再新增 |

## 3. 链路设计

### 3.1 发起链

1. 触发：`POST /internal/orders/refund`（手工/运维/演示）或 surplus 自动判定（`TransactionApplicationService.onPaymentSucceeded`）。
2. transaction 层 `createRefund(orderNo, paymentNo, amountMinor, reason)`：
   - 校验订单 PAID + `Order.getRefundableMinor()` ≥ 本次金额（第二道校验，激活死代码）；
   - 生成 TXRF → 落 `transaction_refunds`（REQUESTED；命中幂等键直接回放）；
   - `PaymentGateway.refund(transactionRefundNo, paymentNo, amountMinor, currencyCode, reason)`。
3. payment 受理（`RefundApplicationService.createRefund`）：
   - intake 悲观锁（按 paymentNo 串行化，H1 不变）→ `RefundPolicy.decide()` 权威金额校验（累计已退 + 本次 ≤ 实付，在途保守占用）→ 非 SUCCEEDED 落 REJECTED；
   - 落 `refunds`（PMRF + transaction_refund_no）→ `payment_attempts` 落 REFUND 尝试（带金额列，spec 018）；
   - 渠道受理（异步模式返回 PROCESSING；同步模式保留可配）；
   - 响应携带 `paymentRefundNo`。
4. transaction 层回填 `transaction_refunds.payment_refund_no`，状态 REQUESTED → PROCESSING。

### 3.2 回调链（新增）

1. mock 渠道延迟回调 payment 退款回调端点（验参防重放 + 防伪造）。
2. `RefundResultProcessor`（对标 PaymentResultProcessor）**统一后处理**——同步受理成功 / 异步回调 / resolve 人工收敛三路全部收敛到此处：
   - payment_attempts 终态（channel_reference 唯一约束兜底幂等）；
   - refunds 状态机终态（终态吸收冲突结果）；
   - payments 退款侧口径（加 `refunded_minor` 或状态补 REFUNDING/PARTIALLY_REFUNDED/REFUNDED——T108 实施时按最小改动定稿）；
   - ledger 冲正：借 MERCHANT_PAYABLE / 贷 CUSTOMER_CASH，幂等键 `REFUND:{PMRF}`（顺手修 G5 双重前缀）；
   - 通知 order `POST /internal/orders/on-refund-result`（带 TXRF+PMRF）。
3. order 侧收口（`OnRefundResultHandler`）：
   - transaction 层：退款单终态 + `transactions.refunded_minor` 累加（按 TXRF 寻址，幂等可重入）；
   - order 层：`Order.recordRefund()`（超退二次校验激活）+ OrderStatus 流转（新增 `PARTIALLY_REFUNDED` / `REFUNDED`）；
   - 秒杀商品：catalog 库存回补（幂等键 `refund:{TXRF}:sku:{skuId}`；普通商品不回补，秒杀标记沿用 spec 014 体系）；
   - fulfillment 终止：`POST /internal/fulfillments/on-refund`（spec 018 后按 item 撤 PENDING）；
   - entitlement 撤销沿 fulfillment→entitlement 既定链（order 不直调 entitlement）。
4. 每步失败：重试（幂等可重入）→ 指标 + 审计留痕 → 对账兜底（不回滚退款成功事实，禁 2PC，沿既有 Saga 原则）。

### 3.3 编排归属（T108 实施定稿 ✅ 2026-09-09）

原则对齐 ADR-0054（order 是业务编排者，payment 是能力提供方），T108 实施定稿如下：

- **payment 保留**：渠道事实（refunds 状态机 + payment_attempts REFUND 尝试）+ 记账冲正（ledger）+ order 通知（RefundResultNotification 双号）。
- **payment 删除**：原 refund 包对 fulfillment/entitlement 的直调扇出与 RefundPostProcessOrchestrator / RefundPostProcessAttempt 体系（「最小迁移 + 不留双路径」）——履约终止/权益撤销/秒杀回补全部移交 order 侧 `onRefundResult` 收口（T105 已实现）。`RefundPostProcessRequest/Response` DTO 与 `refund_post_process_attempts` 表随之下线（表留存量不清理）。
- **三路收敛**：同步受理（SYNC）/ 渠道异步回调（CHANNEL_CALLBACK，`POST /internal/refunds/{refundNo}/channel-callback`，验签过滤器扩展覆盖）/ resolve 人工收敛（RESOLVE）全部收敛到新 `RefundResultProcessor`——状态机终态 → 记账冲正 → 通知 order，一条路径。
- **payments 退款口径定稿**：payments 表**不加列、不改状态**——退款事实权威台账 = `refunds`（累计/终态/幂等键=TXRF）+ `payment_attempts`（REFUND 尝试持渠道流水）；对账经 `RefundFactsService`（PMRF + channel_reference）抽取。避免 payments.refunded_minor 与 refunds 双路径漂移；支付单保留 SUCCEEDED 事实（退款是补偿动作，不回滚原单）。
- **受理在途语义**：mock 渠道异步受理返回 UNKNOWN（通信成功 + 业务无结论，带受理流水号），退款停待收敛态；命令响应 `RefundCommandResponse.status` 恒映射 `PROCESSING` 语义（order 侧 `accept()` 只认非 REJECTED，天然兼容）。
- **记账幂等键**：统一 `REFUND:{PMRF}`——`"REFUND:"` 前缀由 `RefundFeignLedgerPostingGateway` 单点添加，调用方只传 PMRF（修 G5 双重前缀）。
- **mock 渠道**：`payment.channel.refund-async`（默认 true）+ `refund-async-delay-ms`；同步模式保留可配。

## 4. 业内对比（写入 spec 依据）

| 模式 | 单号生成 | 幂等 | 本设计对应 |
|---|---|---|---|
| 微信（out_refund_no） | 商户/上层生成，渠道复用，重试同号 | 同号只退一次 | 幂等键=TXRF、可重入回放 |
| Stripe（re_xxx） | 平台生成，商户记录映射 | 平台幂等 | PMRF 由 payment 生成、transaction 记录 |
| 国内中台双层单据 | 交易系统退款单 + 支付系统执行单并存互关联 | 两层各自幂等 | **采纳**：TXRF + PMRF 互记 |

金额校验对比：支付中台「原单累加已退金额、校验不可超」→ 权威在支付层（原单持有者）；微信/支付宝渠道侧亦校验（总额/次数）。本项目两层校验 = 业内标准防御姿态。

## 5. 迁移与测试

- 迁移脚本 `deployment/schema/019-order-driven-refund.sql`（幂等 information_schema + PREPARE；禁用 MariaDB 方言）：order 库建 transaction_refunds + transactions 加列；payment 库 refunds 加 transaction_refund_no。
- 测试：TransactionApplicationService（创建/重试可重入/状态推进）、OrderApplicationService（状态流转/超退二次校验/秒杀回补触发）、RefundApplicationService（PMRF/幂等键切换）、RefundResultProcessor（三路收敛/回调重放）、contract test（双号互传）；收尾 `mvn -o clean verify -fae`。
- demo 冒烟见 acceptance.md。
