# Tasks: 015-multi-channel-payment

> 每个任务完成后跑 `mvn -o clean verify -fae`（或对应模块 `mvn -o -pl <m> -am test`）门禁，绿色再继续。最后统一全量门禁 + 精确提交。

## P0 数据底座
- [ ] `03-payment-schema.sql`：去 `uk_payments_transaction_id` 唯一 → 普通索引；新增 `attempt_seq` + `idx_payments_txn_seq`
- [ ] 幂等键改造：`idempotencyKey = "payment:" + orderId + ":" + channelCode + ":" + attemptSeq`（修 C2）
- [ ] `Payment` 领域增 `attemptSeq`；`PaymentEntity` 增列；`PaymentRepository.countByTransactionId`；`MybatisPaymentRepository` 实现
- [ ] 迁移脚本 `015-payment-multi-attempt.sql`（可重放）
- [ ] `PaymentPersistence.insertPending` 按 `count(transaction_id)+1` 算 `attemptSeq`

## P1 一交易多支付单（INV-2 前提）
- [ ] `OrderApplicationService.doCreateOrder` 移除 `paymentGateway.createPayment(...)`；响应 `paymentId/paymentStatus/payUrl` 置 null
- [ ] 新增 `POST :8083/orders/{orderId}/payments`（body channelCode）→ 调 `payment-service POST /payments`；交易单 `start()`
- [ ] `CreatePaymentResponse` 扩 `attemptSeq` / `channelCode`（兼容构造）
- [ ] 补充/调整相关测试（下单不建单、换渠道新单、旧单 FAILED）

## P2 回调语义（INV-1 / INV-2）
- [ ] `PaymentResultProcessor.applyAndNotify`：SUCCESS→更新支付单+记账+RPC通知order；FAILURE→只更新支付单FAILED+attempt不通知order；UNKNOWN→markUnknown
- [ ] order `onPaymentSucceeded` 非 PENDING_PAYMENT 返明确 **409** `ORDER_NOT_PAYABLE`（修 C5）
- [ ] `OrderFeignClient` 识别 409 抛 typed exception（供 P4 捕获）
- [ ] 同步 charge 路径与回调路径一致触发 order 通知

## P3 退款合并（架构变更，服务数 10→9）
- [ ] `refund-service/**` 代码迁入 `payment-service/.../com/payment/payment/refund/**`
- [ ] 端口 8085→8084；新增 `EntitlementFeignClient`；删 `PaymentRefundGateway`+`PaymentRefundFeignClient` 改内部调用
- [ ] 4 张退款表迁 `payment` 库（DDL）
- [ ] 删 `refund-service/` 模块 + 根 `pom.xml` module；`ServiceBoundaryTest.SERVICES` 移除 `"refund"`
- [ ] 同步 docker-compose / start-all / stop-all / prometheus / demo / mock-channel-web yml / ADR-0001/0006 / project-structure / systems / constitution / roadmap / runbook / README

## P4 自动退款
- [ ] 捕获 order 409 → 触发自动退款，保留支付单 SUCCEEDED（C4）
- [ ] 内部调用合并后的退款能力，幂等键 `"autorefund:" + paymentId`
- [ ] 同步重试 3 次指数退避 200ms，仍失败 → 指标 `payment.auto_refund_failed` + ERROR 日志转人工
- [ ] 覆盖「重复成功」+「已关闭订单」两场景的测试

## P5 三渠道 mock + 收银台
- [ ] `MockChannelAdapter.charge()` 按 channelCode 生成带前缀引用（alipay-ref-/wechat-ref-/douyin-ref-，未知回落 mock-ref-）；不改构造签名
- [ ] `cashier.html` 渠道展示 + 换渠道按钮；`demo-ref-`+`Date.now()` 撞键改 `crypto.randomUUID()`

## P6 流量脚本
- [ ] `lib.sh` 零 fork：`httpq` / `jnum` / `jstr`
- [ ] `traffic-gen.sh`：下单→选渠道→callback（成败按概率）→5% UNKNOWN 延迟2s→resolve→换渠道再付；补偿式 sleep 控频（默认 2 TPS）；自建大库存 SKU；可配参数；`trap`+JSONL 汇总
- [ ] `stop-traffic.sh`
- [ ] `bash -n` 语法检查通过

## P7 文档收口 + 全量门禁
- [ ] ADR 决策文档合并 5~6 条（超前偏离 / 架构合并 / 多支付单 / 自动退款）
- [ ] roadmap / runbook / README / constitution / project-structure / systems 同步
- [ ] `mvn -o clean verify -fae` 全量门禁（含 architecture-tests 10→9）

## 验收报告
- [ ] 跑通 SC-002/003/004 关键场景
- [ ] 编写 015 验收报告
- [ ] 仅精确 git add/commit 015 文件（不碰其他任务 staged 文件），提交到 `feature/015-multi-channel-payment`
