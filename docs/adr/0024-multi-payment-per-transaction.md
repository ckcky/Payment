# ADR-0064: 一交易多支付单（Feature 015）——支付单与订单解耦、退款域并入 payment-service

- 状态：✅ Accepted（2026-09-04）
- 关联：ADR-0062（业务单号）、ADR-0063（跨系统业务单号关联）、spec 015、INV-1/INV-2
- 需求源头：负责人「一订单对应一交易单，一交易单可关联多支付单；用户每选一个支付方式就新建一张支付单；
  回调成功→更新支付单+RPC通知订单（订单 PAID+交易 SUCCEEDED+订单层扣库存）；回调失败只更新支付单不动订单；
  换渠道旧支付单保留 FAILED 不调 Payment.close()；退款服务并入 payment-service；三渠道 mock（支付宝/微信/抖音）」

## 背景

原模型在下单时即创建支付单（一订单一支付单，`uk_payments_transaction_id` 唯一约束锁死）。
用户换渠道重付时无处安放第二笔支付：唯一约束顶死、账本幂等键不含支付单维度导致第二笔成功
静默少记账（C2）、`markPaid` 对非 PENDING_PAYMENT 抛异常被支付侧吞掉（C5，回调方无感知）。

## 决策

1. **下单不建支付单，显式选渠道才建**：`POST /orders` 只建订单+交易单；新增
   `POST /orders/{ref}/payments {channelCode}`，每次调用新建一张支付单（attemptSeq = 同交易单笔数+1）。
2. **去掉 `uk_payments_transaction_id` 唯一约束**（改普通索引），新增
   `attempt_seq INT` 列与 `(transaction_id, attempt_seq)` 组合索引；幂等键改由 payment 侧生成：
   `payment:{orderNo}:{channelCode}:{attemptSeq}`（调用方显式传 key 时仍以 key 去重，保持 T018 契约）。
3. **回调语义（INV-1/INV-2）**：SUCCESS → 更新支付单+账本+RPC 通知 order（order 层一次性推进
   PAID+SUCCEEDED+确认扣库存，扣库存必须由 order-service 发起）；FAILURE → 只更新支付单 FAILED，
   不通知 order；UNKNOWN → 进主动查询收敛。同订单另一张支付单的成功回调不被幂等吸收——
   order 识别后走自动退款（见 4）。
4. **C5 修复 + 自动退款闭环**：order 对不可支付订单返回 409 `ORDER_NOT_PAYABLE`（不再吞异常）；
   payment 侧 Feign 解码 409 抛 `OrderNotPayableException`，触发进程内自动退款
   （幂等键 `autorefund:<paymentNo>`，同步重试 3 次指数退避 200ms 起，失败记
   `payment_auto_refund_failed_total` + ERROR 日志转人工）。
5. **退款域并入 payment-service**（服务数 10→9）：`com.payment.refund` 包整体迁入
   payment-service（@SpringBootApplication/MapperScan/FeignClients 双包扫描）；refund→payment 的
   Feign 自调用改进程内直调（`LocalPaymentRefundGateway`）；同名 Feign 客户端加 `contextId`
   区分、`FeignLedgerPostingGateway` 改名 `RefundFeignLedgerPostingGateway` 防止 bean 冲突；
   端口 8085 退役，退款 API 随 payment 8084 暴露。
6. **三渠道 mock**：`MockChannelAdapter.charge()` 按 channelCode 生成带前缀渠道引用
   （alipay-/wechat-/douyin-/mock-）；收银台展示当前渠道并提供换渠道按钮（经
   mock-channel-web `/proxy/order` 代理，零 CORS 改动）；回调引用改 `crypto.randomUUID()` 防撞键。

## 后果

- 支付单生命周期与订单解耦，换渠道/重试天然多单，账本记账键唯一性由 attemptSeq 保证。
- 服务数 10→9，部署与演示脚本（start-all/start-demo/监控/k6）同步收敛到 8084。
- 遗留：refund-service 目录因 IDE 进程锁无法物理删除（已移出构建路径——根 pom 不再引用；
  文件副本完整迁移至 payment-service 后，目录内容为冗余残留，待编辑器释放后手动删除）。

## 验收

见 `docs/specs/015-multi-channel-payment/acceptance.md`（SC-001~007 逐条对照 + 全量
`mvn -o clean verify -fae` 门禁）。

## 后续演进

> **Superseded by ADR-0054**（2026-09-06，`docs/adr/0025-order-payment-orchestration.md`）：本 ADR **第 4 条**（order 返回 409 `ORDER_NOT_PAYABLE` → payment 捕获后自发起自动退款）已被 ADR-0054 取代——自动退款的**决策与发起**归属 order-service 的 transaction 层（以 `transactionNo + paymentNo` 发起），payment-service 退回能力提供方。其余条款（一交易多支付单、退款域并入 payment-service、三渠道 mock）**保持不变**。
